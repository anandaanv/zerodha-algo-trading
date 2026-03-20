// Simplified TradingView Datafeed
import { Client, type StompSubscription } from '@stomp/stompjs';
import { fetchSymbols, fetchOHLC, fetchIntervalMapping, subscribeSymbol, type SymbolItem } from './tvApi';

const resolutionToInterval: Record<string, string> = {};
let intervalMapping: Record<string, string> = {};
let symbolsCache: SymbolItem[] = [];
let symbolsCacheTime = 0;
const CACHE_DURATION = 5 * 60 * 1000;
const lastBarsCache = new Map<string, any>();
const getBarsCache = new Map<string, { data: any[], timestamp: number, oldestTime: number }>();
const BARS_CACHE_DURATION = 60 * 1000;

// ── STOMP real-time state ────────────────────────────────────────────────────
let stompClient: Client | null = null;
// Map from subscriberUID → StompSubscription (active)
const activeSubscriptions = new Map<string, {
  sub: StompSubscription;
  symbol: string;
  interval: string;
}>();
// Queue of subscribe calls that arrived before the connection was established
const pendingSubscriptions: Array<() => void> = [];

function ensureStompClient(): Client {
  if (stompClient) return stompClient;

  const wsUrl = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws`;
  const jwtToken = localStorage.getItem('auth_token');

  stompClient = new Client({
    brokerURL: wsUrl,
    connectHeaders: jwtToken ? { Authorization: `Bearer ${jwtToken}` } : {},
    reconnectDelay: 5000,
    onConnect: () => {
      // Flush all subscriptions that were queued before connection
      const pending = pendingSubscriptions.splice(0);
      pending.forEach(fn => fn());
    },
    onStompError: (frame) => {
      console.error('[STOMP] error', frame);
    },
  });

  stompClient.activate();
  return stompClient;
}

// ── End STOMP state ──────────────────────────────────────────────────────────

const configurationData = {
  supported_resolutions: ['1', '3', '5', '15', '30', '60', '120', '1D', '1W', '1M'],
  exchanges: [{ value: 'NSE', name: 'NSE', desc: 'NSE' }],
  symbols_types: [{ name: 'Stock', value: 'stock' }],
};

async function getAllSymbols(): Promise<SymbolItem[]> {
  const now = Date.now();
  if (symbolsCache.length > 0 && now - symbolsCacheTime < CACHE_DURATION) return symbolsCache;
  try {
    symbolsCache = await fetchSymbols('');
    symbolsCacheTime = now;
    return symbolsCache;
  } catch {
    return [];
  }
}

const datafeed: any = {
  onReady: (callback: any) => {
    fetchIntervalMapping()
      .then((mapping) => {
        intervalMapping = mapping;
        for (const [key, value] of Object.entries(mapping)) {
          if (key === '1m') resolutionToInterval['1'] = value;
          else if (key === '3m') resolutionToInterval['3'] = value;
          else if (key === '5m') resolutionToInterval['5'] = value;
          else if (key === '15m') resolutionToInterval['15'] = value;
          else if (key === '30m') resolutionToInterval['30'] = value;
          else if (key === '1h') resolutionToInterval['60'] = value;
          else if (key === '2h') resolutionToInterval['120'] = value;
          else if (key === '1d') resolutionToInterval['1D'] = value;
          else if (key === '1w') resolutionToInterval['1W'] = value;
          else if (key === '1M') resolutionToInterval['1M'] = value;
        }
        setTimeout(() => callback(configurationData), 0);
      })
      .catch(() => setTimeout(() => callback(configurationData), 0));
  },

  searchSymbols: async (userInput: string, exchange: string, symbolType: string, onResultReadyCallback: any) => {
    try {
      const symbols = await fetchSymbols(userInput);
      const results = symbols.map((s) => ({
        symbol: s.tradingsymbol,
        full_name: s.tradingsymbol,
        description: s.name || s.tradingsymbol,
        exchange: s.exchange || 'NSE',
        ticker: s.tradingsymbol,
        type: 'stock',
      }));
      onResultReadyCallback(results);
    } catch {
      onResultReadyCallback([]);
    }
  },

  resolveSymbol: async (symbolName: string, onSymbolResolvedCallback: any, onResolveErrorCallback: any) => {
    try {
      // Try to fetch the specific symbol directly
      const symbols = await fetchSymbols(symbolName);
      const symbolItem = symbols.find((s) => s.tradingsymbol === symbolName);

      if (!symbolItem) {
        // If not found, try searching with fallback
        onResolveErrorCallback('unknown_symbol');
        return;
      }

      onSymbolResolvedCallback({
        ticker: symbolItem.tradingsymbol,
        name: symbolItem.tradingsymbol,
        description: symbolItem.name || symbolItem.tradingsymbol,
        type: 'stock',
        session: '0915-1530',
        timezone: 'Asia/Kolkata',
        exchange: symbolItem.exchange || 'NSE',
        minmov: 1,
        pricescale: 100,
        has_intraday: true,
        has_daily: true,
        has_weekly_and_monthly: true,
        supported_resolutions: configurationData.supported_resolutions,
        volume_precision: 0,
        data_status: 'streaming',
        format: 'price',
      });
    } catch {
      onResolveErrorCallback('failed');
    }
  },

  getBars: async (symbolInfo: any, resolution: string, periodParams: any, onHistoryCallback: any, onErrorCallback: any) => {
    const { from, to, firstDataRequest } = periodParams;

    try {
      const interval = resolutionToInterval[resolution] || intervalMapping['1h'] || 'OneHour';
      const cacheKey = `${symbolInfo.name}_${interval}`;
      const cachedData = getBarsCache.get(cacheKey);

      // Use cached data if available
      if (cachedData && (Date.now() - cachedData.timestamp) < BARS_CACHE_DURATION) {
        const { data: allBars, oldestTime } = cachedData;
        // from and to are in SECONDS, but bar.time is in MILLISECONDS
        const fromMs = from * 1000;
        const toMs = to * 1000;
        if (fromMs < oldestTime) {
          onHistoryCallback([], { noData: true });
          return;
        }
        const filteredBars = allBars.filter(bar => bar.time >= fromMs && bar.time < toMs);
        onHistoryCallback(filteredBars.length > 0 ? filteredBars : allBars, { noData: false });
        return;
      }

      // Fetch fresh data from API
      const rows = await fetchOHLC(symbolInfo.name, interval);
      if (!rows || rows.length === 0) {
        onHistoryCallback([], { noData: true });
        return;
      }

      // API returns timestamps in SECONDS, TradingView expects MILLISECONDS
      const bars = rows
        .map((row) => {
          const timeValue = (row.time || row.timestamp || 0) as number;
          const bar = {
            time: timeValue * 1000, // Convert to milliseconds
            open: Number(row.open),
            high: Number(row.high),
            low: Number(row.low),
            close: Number(row.close),
          };
          // Only add volume if it exists and is valid
          if (row.volume != null && !isNaN(Number(row.volume))) {
            (bar as any).volume = Number(row.volume);
          }
          return bar;
        })
        .filter(bar => bar.time > 0 && bar.open > 0 && bar.high > 0 && bar.low > 0 && bar.close > 0)
        .sort((a, b) => a.time - b.time);

      if (bars.length === 0) {
        onHistoryCallback([], { noData: true });
        return;
      }

      const oldestTime = bars[0].time;

      if (firstDataRequest) {
        lastBarsCache.set(symbolInfo.ticker || symbolInfo.name, bars[bars.length - 1]);
      }

      // Cache the data
      getBarsCache.set(cacheKey, { data: bars, timestamp: Date.now(), oldestTime });

      // Filter for requested range (from/to are in seconds, bar.time is in milliseconds)
      const fromMs = from * 1000;
      const toMs = to * 1000;
      const filteredBars = bars.filter(bar => bar.time >= fromMs && bar.time < toMs);

      if (filteredBars.length === 0) {
        onHistoryCallback(bars, { noData: false });
      } else {
        onHistoryCallback(filteredBars, { noData: false });
      }
    } catch (error) {
      console.error('[getBars] Error:', error);
      onErrorCallback(String(error));
    }
  },

  subscribeBars: (
    symbolInfo: any,
    resolution: string,
    onRealtimeCallback: (bar: any) => void,
    subscriberUID: string,
    _onResetCacheNeededCallback: () => void,
  ) => {
    const interval = resolutionToInterval[resolution] || 'OneHour';
    const symbol = symbolInfo.ticker || symbolInfo.name;

    // Ensure the backend is streaming ticks for this symbol
    subscribeSymbol(symbol);

    const topic = `/topic/bars/${symbol}/${interval}`;

    const client = ensureStompClient();

    const doSubscribe = () => {
      const stompSub = client.subscribe(topic, (message) => {
        try {
          const msg = JSON.parse(message.body);
          // Server sends epoch seconds; TradingView expects milliseconds
          onRealtimeCallback({
            time: msg.time * 1000,
            open: msg.open,
            high: msg.high,
            low: msg.low,
            close: msg.close,
            volume: msg.volume ?? 0,
          });
        } catch (e) {
          console.error('[subscribeBars] parse error', e);
        }
      });
      activeSubscriptions.set(subscriberUID, { sub: stompSub, symbol, interval });
    };

    if (client.connected) {
      doSubscribe();
    } else {
      // Queue until onConnect fires
      pendingSubscriptions.push(doSubscribe);
    }
  },

  unsubscribeBars: (subscriberUID: string) => {
    const entry = activeSubscriptions.get(subscriberUID);
    if (entry) {
      try { entry.sub.unsubscribe(); } catch { /* already gone */ }
      activeSubscriptions.delete(subscriberUID);
    }
    // Disconnect STOMP if no more subscriptions
    if (activeSubscriptions.size === 0 && stompClient?.connected) {
      stompClient.deactivate();
      stompClient = null;
    }
  },
};

export default datafeed;
