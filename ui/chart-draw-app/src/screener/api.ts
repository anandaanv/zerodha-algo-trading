import {apiFetch} from "../config/api";
import {withAuth} from "../utils/apiHelper";

export type UpsertPayload = {
  script: string;
  timeframe: string;
  mapping?: Record<string, unknown>;
  workflow?: string[];
  // Either provide promptId or promptJson; promptId takes precedence
  promptId?: string;
  promptJson?: string;
  // Chart aliases to send to AI
  charts?: string[];
  // Scheduling: array of run configurations to persist with the screener
  runConfigs?: RunConfig[];
};

export type ScreenerResponse = {
  id: number;
  timeframe: string;
  script: string;
  configJson: string;
  promptJson: string;
  chartsJson: string;
};

export type IntervalUiMapping = Record<string, string>; // { "1m": "OneMinute", ... }

// Scheduling types
export type RunConfig = {
  timeframe: string;
  symbols: string[];
};

export async function getIntervalUiMapping(): Promise<IntervalUiMapping> {
  const res = await apiFetch("/api/intervals/mapping", withAuth());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function getSeriesEnums(): Promise<string[]> {
  const res = await apiFetch("/api/screener-meta/series-enums", withAuth());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function createScreener(payload: UpsertPayload): Promise<ScreenerResponse> {
  const res = await apiFetch("/api/screeners", withAuth({
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  }));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function updateScreener(id: number, payload: UpsertPayload): Promise<ScreenerResponse> {
  const res = await apiFetch(`/api/screeners/${id}`, withAuth({
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  }));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function listScreeners(): Promise<ScreenerResponse[]> {
  const res = await apiFetch("/api/screeners", withAuth());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function getScreener(id: number): Promise<ScreenerResponse> {
  const res = await apiFetch(`/api/screeners/${id}`, withAuth());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function runScreener(id: number, params: { symbol: string; nowIndex: number; timeframe?: string }): Promise<void> {
  const q = new URLSearchParams({
    symbol: params.symbol,
    nowIndex: String(params.nowIndex),
    ...(params.timeframe ? { timeframe: params.timeframe } : {}),
  }).toString();
  const res = await apiFetch(`/api/screeners/${id}/run?${q}`, withAuth({ method: "POST" }));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Run failed: ${res.status}`);
  }
}

export async function validateScreenerScript(script: string): Promise<{ ok: boolean; error?: string }> {
  const res = await apiFetch("/api/screeners/validate", withAuth({
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ script }),
  }));
  if (!res.ok) {
    const text = await res.text();
    return { ok: false, error: text || `Validate request failed: ${res.status}` };
  }
  return res.json();
}

export async function updateSubscriptions(id: number): Promise<void> {
  const res = await apiFetch(`/api/screeners/${id}/subscribe`, withAuth({ method: "POST" }));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Update subscriptions failed: ${res.status}`);
  }
}
