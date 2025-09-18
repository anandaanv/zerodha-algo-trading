export type UpsertPayload = {
  script: string;
  timeframe: string;
  mapping?: Record<string, unknown>;
  workflow?: string[];
  promptJson?: string;
  charts?: string[];
};

export type ScreenerResponse = {
  id: number;
  timeframe: string;
  script: string;
  configJson: string;
  promptJson: string;
  chartsJson: string;
};

export async function createScreener(payload: UpsertPayload): Promise<ScreenerResponse> {
  const res = await fetch('/api/screeners', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function listScreeners(): Promise<ScreenerResponse[]> {
  const res = await fetch('/api/screeners');
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function getScreener(id: number): Promise<ScreenerResponse> {
  const res = await fetch(`/api/screeners/${id}`);
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function runScreener(
  id: number,
  params: { symbol: string; nowIndex: number; timeframe?: string }
): Promise<void> {
  const q = new URLSearchParams({
    symbol: params.symbol,
    nowIndex: String(params.nowIndex),
    ...(params.timeframe ? { timeframe: params.timeframe } : {})
  });
  const res = await fetch(`/api/screeners/${id}/run?` + q.toString(), { method: 'POST' });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Run failed: ${res.status}`);
  }
}
