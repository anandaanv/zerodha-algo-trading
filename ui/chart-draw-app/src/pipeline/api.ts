import { apiFetch } from "../config/api";
import { withAuth } from "../utils/apiHelper";
import { TradeSignal, TradeOrder } from "./types";

async function apiJson<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await apiFetch(path, withAuth(options));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `HTTP ${res.status}`);
  }
  return res.json();
}

export const getSignal = (id: number): Promise<TradeSignal> =>
  apiJson<TradeSignal>(`/api/trade-signals/${id}`);

export const getSignalsByIds = (ids: number[]): Promise<TradeSignal[]> =>
  apiJson<TradeSignal[]>(`/api/trade-signals/by-ids?ids=${ids.join(",")}`);

export const getAllSignals = (status?: string): Promise<TradeSignal[]> => {
  const path = status ? `/api/trade-signals?status=${status}` : `/api/trade-signals`;
  return apiJson<TradeSignal[]>(path);
};

export const getOrdersForSignal = (signalId: number): Promise<TradeOrder[]> =>
  apiJson<TradeOrder[]>(`/api/trade-signals/${signalId}/orders`);

export const getAllOrders = (): Promise<TradeOrder[]> =>
  apiJson<TradeOrder[]>(`/api/trade-orders/`);
