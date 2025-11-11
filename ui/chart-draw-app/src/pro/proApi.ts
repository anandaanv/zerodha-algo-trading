// ui/chart-draw-app/src/pro/proApi.ts

/**
 * Get service token from URL query parameters
 */
function getServiceToken(): string | null {
  const params = new URLSearchParams(window.location.search);
  return params.get('servicetoken');
}

/**
 * Add service token to URL if present
 */
function addServiceToken(url: URL): void {
  const token = getServiceToken();
  if (token) {
    url.searchParams.set('servicetoken', token);
  }
}

/**
 * Add service token to fetch options as header if present
 */
function addServiceTokenHeader(options: RequestInit = {}): RequestInit {
  const token = getServiceToken();
  if (token) {
    return {
      ...options,
      headers: {
        ...options.headers,
        'X-Service-Token': token,
      },
    };
  }
  return options;
}

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

export type PeriodItem = { multiplier: number; timespan: string; text: string };

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
  const q = (query || "").trim();
  try {
    const url = new URL("/api/symbols", window.location.origin);
    if (q) url.searchParams.set("query", q);
    addServiceToken(url);
    const res = await fetch(url.toString());
    if (res.ok) {
      const arr = await res.json();
      return (Array.isArray(arr) ? arr : []).map((it: any) => {
        if (!it) return { tradingsymbol: String(it) } as SymbolItem;
        if (typeof it === "string") return { tradingsymbol: it } as SymbolItem;
        return {
          tradingsymbol: (it.tradingsymbol ?? it.tradingsymbol ?? String(it)).toString(),
          name: it.name ?? undefined,
          lastPrice: typeof it.lastPrice === "number" ? it.lastPrice : (it.lastPrice ? Number(it.lastPrice) : undefined),
          expiry: it.expiry ?? undefined,
          strike: it.strike ?? undefined,
          instrumentType: it.instrumentType ?? undefined,
          segment: it.segment ?? undefined,
          exchange: it.exchange ?? undefined,
          lotSize: typeof it.lotSize === "number" ? it.lotSize : (it.lotSize ? Number(it.lotSize) : undefined),
          tickSize: typeof it.tickSize === "number" ? it.tickSize : (it.tickSize ? Number(it.tickSize) : undefined),
        } as SymbolItem;
      });
    }
  } catch {
    /* ignore */
  }
  const base = ["TCS", "INFY", "RELIANCE", "HDFCBANK", "SBIN", "TATASTEEL", "ITC"];
  const filtered = base.filter((s) => s.toLowerCase().includes(q.toLowerCase()));
  return filtered.map((s) => ({ tradingsymbol: s }));
}

export async function fetchIntervalMapping(): Promise<Record<string, string>> {
  const url = new URL("/api/intervals/mapping", window.location.origin);
  addServiceToken(url);
  const res = await fetch(url.toString());
  if (!res.ok) throw new Error("interval mapping fetch failed");
  return await res.json();
}

export async function fetchPeriodItems(): Promise<PeriodItem[]> {
  const url = new URL("/api/intervals/periods", window.location.origin);
  addServiceToken(url);
  const res = await fetch(url.toString());
  if (!res.ok) throw new Error("period items fetch failed");
  return await res.json();
}

export async function fetchOHLC(symbol: string, interval: string): Promise<OhlcRow[]> {
  const url = new URL("/api/ohlc", window.location.origin);
  url.searchParams.set("symbol", symbol);
  url.searchParams.set("interval", interval);
  addServiceToken(url);
  const res = await fetch(url.toString());
  if (!res.ok) throw new Error(`ohlc fetch failed ${res.status}`);
  return await res.json();
}

export async function saveOverlaysToServer(symbol: string, period: string, overlaysPayload: Record<string, any>): Promise<void> {
  const url = new URL("/api/chart-state", window.location.origin);
  addServiceToken(url);
  await fetch(url.toString(), addServiceTokenHeader({
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ symbol, period, overlays: overlaysPayload }),
  }));
}

export async function loadOverlaysFromServer(symbol: string, period: string): Promise<{ overlays?: Record<string, any> } | null> {
  const url = new URL("/api/chart-state", window.location.origin);
  url.searchParams.set("symbol", symbol);
  url.searchParams.set("period", String(period));
  addServiceToken(url);
  const res = await fetch(url.toString());
  if (!res.ok) return null;
  return await res.json();
}
