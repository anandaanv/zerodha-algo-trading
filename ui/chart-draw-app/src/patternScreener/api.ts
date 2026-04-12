import { apiFetch, getApiUrl } from "../config/api";
import { withAuth } from "../utils/apiHelper";
import { PatternScreener, PatternScreenerRequest, PatternScreenerRun, PatternScreenerRunResult } from "./types";

async function apiJson<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await apiFetch(path, withAuth(options));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `HTTP ${res.status}`);
  }
  return res.json();
}

export const listScreeners = () => apiJson<PatternScreener[]>("/api/pattern-screener");

export const createScreener = (req: PatternScreenerRequest) =>
  apiJson<PatternScreener>("/api/pattern-screener", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });

export const updateScreener = (id: number, req: PatternScreenerRequest) =>
  apiJson<PatternScreener>(`/api/pattern-screener/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });

export const deleteScreener = (id: number) =>
  apiJson<void>(`/api/pattern-screener/${id}`, { method: "DELETE" });

export const triggerRun = (id: number) =>
  apiJson<{ status: string }>(`/api/pattern-screener/${id}/run`, { method: "POST" });

export const getRuns = (id: number) =>
  apiJson<PatternScreenerRun[]>(`/api/pattern-screener/${id}/runs`);

export const getRunResults = (id: number, runId: number) =>
  apiJson<PatternScreenerRunResult[]>(`/api/pattern-screener/${id}/runs/${runId}/results`);
