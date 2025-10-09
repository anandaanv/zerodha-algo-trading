import type { Trade } from "./types";

const API_BASE = (import.meta as any).env?.VITE_API_BASE || "/api";

function toIsoDate(d?: Date) {
  return d ? d.toISOString() : undefined;
}

export async function fetchTrades(params: {
  from?: Date;
  to?: Date;
  status?: string; // e.g. "ACTIVE"
  page?: number;
  size?: number;
  script?: string;
  timeframe?: string;
  side?: string;
  open?: boolean;
}): Promise<Trade[]> {
  const q = new URLSearchParams();
  if (params.from) q.set("from", toIsoDate(params.from)!);
  if (params.to) q.set("to", toIsoDate(params.to)!);
  if (params.status) q.set("status", params.status);
  if (params.script) q.set("script", params.script);
  if (params.timeframe) q.set("timeframe", params.timeframe);
  if (params.side) q.set("side", params.side);
  if (params.open !== undefined) q.set("open", String(params.open));
  if (params.page != null) q.set("page", String(params.page));
  if (params.size != null) q.set("size", String(params.size));

  const url = `${API_BASE}/trades${q.toString() ? `?${q.toString()}` : ""}`;
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) {
    throw new Error(`Failed to fetch trades: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as Trade[];
}

export async function fetchTradeById(id: string | number): Promise<Trade> {
  const url = `${API_BASE}/trades/${id}`;
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) {
    throw new Error(`Failed to fetch trade ${id}: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as Trade;
}
