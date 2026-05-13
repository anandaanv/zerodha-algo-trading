// TradingView API helper - reuses existing API infrastructure
import { addServiceTokenToUrl, withAuth } from '../utils/apiHelper';
import { apiFetch, getApiUrl } from '../config/api';

export type SymbolItem = {
  tradingsymbol: string;
  name?: string;
  lastPrice?: number;
  expiry?: string;
  strike?: number;
  instrumentType?: string;
  segment?: string;
  exchange?: string;
  lotSize?: number;
  tickSize?: number;
};

export type OhlcRow = {
  time?: number;
  timestamp?: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume?: number;
};

export async function fetchSymbols(query: string): Promise<SymbolItem[]> {
  const q = (query || '').trim();
  try {
    const url = getApiUrl('/api/symbols');
    // Backend requires query param - use 'A' as default to get some results
    url.searchParams.set('query', q || 'A');
    // Don't add service token - symbols endpoint requires JWT auth only
    const res = await apiFetch(url.toString(), withAuth());
    if (res.ok) {
      const arr = await res.json();
      return (Array.isArray(arr) ? arr : []).map((it: any) => {
        if (!it) return { tradingsymbol: String(it) } as SymbolItem;
        if (typeof it === 'string') return { tradingsymbol: it } as SymbolItem;
        return {
          tradingsymbol: (it.tradingsymbol ?? String(it)).toString(),
          name: it.name ?? undefined,
          lastPrice: typeof it.lastPrice === 'number' ? it.lastPrice : it.lastPrice ? Number(it.lastPrice) : undefined,
          expiry: it.expiry ?? undefined,
          strike: it.strike ?? undefined,
          instrumentType: it.instrumentType ?? undefined,
          segment: it.segment ?? undefined,
          exchange: it.exchange ?? undefined,
          lotSize: typeof it.lotSize === 'number' ? it.lotSize : it.lotSize ? Number(it.lotSize) : undefined,
          tickSize: typeof it.tickSize === 'number' ? it.tickSize : it.tickSize ? Number(it.tickSize) : undefined,
        } as SymbolItem;
      });
    }
  } catch {
    /* ignore */
  }
  const base = ['TCS', 'INFY', 'RELIANCE', 'HDFCBANK', 'SBIN', 'TATASTEEL', 'ITC'];
  const filtered = base.filter((s) => s.toLowerCase().includes(q.toLowerCase()));
  return filtered.map((s) => ({ tradingsymbol: s }));
}

export async function fetchIntervalMapping(): Promise<Record<string, string>> {
  const url = getApiUrl('/api/intervals/mapping');
  addServiceTokenToUrl(url);
  const res = await apiFetch(url.toString(), withAuth());
  if (!res.ok) throw new Error('interval mapping fetch failed');
  return await res.json();
}

export async function subscribeSymbol(symbol: string): Promise<void> {
  try {
    const url = getApiUrl('/api/kite/subscribe');
    const res = await apiFetch(url.toString(), {
      ...withAuth(),
      method: 'POST',
      headers: { ...withAuth().headers, 'Content-Type': 'application/json' },
      body: JSON.stringify([symbol]),
    });
    if (!res.ok) console.warn(`[subscribeSymbol] server returned ${res.status} for ${symbol}`);
  } catch (e) {
    console.warn('[subscribeSymbol] failed (non-fatal):', e);
  }
}

export async function fetchOHLC(symbol: string, interval: string, from?: number, to?: number): Promise<OhlcRow[]> {
  const url = getApiUrl('/api/ohlc');
  url.searchParams.set('symbol', symbol);
  url.searchParams.set('interval', interval);
  if (from !== undefined && to !== undefined) {
    url.searchParams.set('from', String(from));
    url.searchParams.set('to', String(to));
    url.searchParams.set('fetchLatest', 'false');
  }
  addServiceTokenToUrl(url);
  const res = await apiFetch(url.toString(), withAuth());
  if (!res.ok) throw new Error(`ohlc fetch failed ${res.status}`);
  return await res.json();
}
