import type { Drawing, OhlcBar } from "./types";

const BASE = ""; // same origin

export async function fetchIntervalMapping(): Promise<Record<string, string>> {
  const res = await fetch(`${BASE}/api/intervals/mapping`);
  if (!res.ok) throw new Error(`Failed to fetch interval mapping: ${res.status}`);
  return res.json();
}

export async function fetchOhlc(symbol: string, interval: string): Promise<OhlcBar[]> {
  const res = await fetch(
    `${BASE}/api/ohlc?symbol=${encodeURIComponent(symbol)}&interval=${encodeURIComponent(interval)}`
  );
  if (!res.ok) throw new Error(`Failed to fetch OHLC: ${res.status}`);
  return res.json();
}

export async function listDrawings(
  symbol: string,
  timeframe: string,
  userId?: string
): Promise<Drawing[]> {
  const q = new URLSearchParams({ symbol, timeframe });
  if (userId) q.set("userId", userId);
  const res = await fetch(`${BASE}/api/drawings?${q.toString()}`);
  if (!res.ok) throw new Error(`Failed to list drawings: ${res.status}`);
  return res.json();
}

export async function createDrawing(d: Drawing): Promise<Drawing> {
  const res = await fetch(`${BASE}/api/drawings`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(d),
  });
  if (!res.ok) throw new Error(`Failed to create drawing: ${res.status}`);
  return res.json();
}

export async function updateDrawing(id: number, patch: Partial<Drawing>): Promise<Drawing> {
  const res = await fetch(`${BASE}/api/drawings/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error(`Failed to update drawing: ${res.status}`);
  return res.json();
}

export async function deleteDrawing(id: number): Promise<void> {
  const res = await fetch(`${BASE}/api/drawings/${id}`, { method: "DELETE" });
  if (!res.ok) throw new Error(`Failed to delete drawing: ${res.status}`);
}
