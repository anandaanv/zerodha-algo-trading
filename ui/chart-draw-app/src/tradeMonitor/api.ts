import { apiFetch } from "../config/api";
import { withAuth } from "../utils/apiHelper";

export type TradingSegment = "EQ" | "FUT" | "OPT";
export type TradeOrderStatus = "OPEN" | "CLOSED" | "FAILED";

export interface SegmentConfig {
  id?: number;
  symbol: string;
  segment: TradingSegment;
  enabled: boolean;
  capitalPct: number;
  provider?: string;
  notes?: string;
}

export interface TradeOrder {
  id: number;
  signal?: {
    id: number;
    patternType: string;
    direction: string;
    timeframe: string;
  };
  symbol: string;
  underlyingSymbol: string;
  segment: TradingSegment;
  direction: "LONG" | "SHORT";
  quantity: number;
  lotSize: number;
  entryPrice: number;
  exitPrice?: number;
  entryTime: string;
  exitTime?: string;
  status: TradeOrderStatus;
  exitReason?: string;
  realisedPnl?: number;
  ltp?: number;
  unrealisedPnl?: number;
  underlyingEntryPrice?: number;
  underlyingExitPrice?: number;
  stopLoss?: number;
  target?: number;
  instrumentType: string;
  strike?: number;
  expiry?: string;
  paperTrade: boolean;
}

export interface TradeSummaryDto {
  totalTrades: number;
  openTrades: number;
  winCount: number;
  lossCount: number;
  totalPnlInr: number;
  avgWinInr: number;
  avgLossInr: number;
  winRatePct: number;
}

async function apiJson<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await apiFetch(path, withAuth(options));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `HTTP ${res.status}`);
  }
  return res.json();
}

export const getSegmentConfigs = () =>
  apiJson<SegmentConfig[]>("/api/segment-config/");

export const createSegmentConfig = (cfg: Omit<SegmentConfig, "id">) =>
  apiJson<SegmentConfig>("/api/segment-config/", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(cfg),
  });

export const updateSegmentConfig = (id: number, cfg: SegmentConfig) =>
  apiJson<SegmentConfig>(`/api/segment-config/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(cfg),
  });

export const deleteSegmentConfig = (id: number) =>
  apiFetch(`/api/segment-config/${id}`, withAuth({ method: "DELETE" }));

export async function seedSegmentConfigs(list: 'NIFTY50' | 'FNO', segment: 'EQ' | 'FUT' | 'OPT'): Promise<{ seeded: number }> {
  return apiJson(`/api/segment-config/seed?list=${list}&segment=${segment}`, { method: 'POST' });
}

export async function clearAllSegmentConfigs(): Promise<void> {
  await apiJson('/api/segment-config/all', { method: 'DELETE' });
}

export const getTradeOrders = (status?: string) => {
  const q = status ? `?status=${status}` : "";
  return apiJson<TradeOrder[]>(`/api/trade-orders/${q}`);
};

export const getTradeSummary = () =>
  apiJson<TradeSummaryDto>("/api/trade-orders/summary");

export const triggerScan = () =>
  apiJson<{ created: number }>("/api/scanner/scan-now", { method: "POST" });
