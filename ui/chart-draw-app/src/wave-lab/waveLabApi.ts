import { apiFetch } from '../config/api';

export interface WlWatchlist {
  id: number;
  name: string;
  createdAt: string;
  updatedAt: string;
}

export interface WlWatchlistItem {
  id: number;
  watchlistId: number;
  symbol: string;
  exchange: string;
  displayOrder: number;
}

export interface WlOverlay {
  id: number;
  symbol: string;
  timeframe: string;
  algoName: string;
  drawingJson: string;
  createdAt: string;
}

export interface BatchResult {
  total: number;
  succeeded: number;
  failed: number;
  errors: string[];
}

export interface TriangleRunResponse {
  id: number;
  symbol: string;
  timeframe: string;
  candleCount: number;
  status: string;
  proposerA: string;
  proposerB: string;
  evaluator: string;
  finalTriangleType: string | null;
  finalStatus: string | null;
  finalConfidence: number | null;
  selectedSource: string | null;
  finalReason: string | null;
  proposerAOutputJson: string | null;
  proposerBOutputJson: string | null;
  evaluatorOutputJson: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

function authHeaders(): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    'Authorization': 'Bearer ' + (localStorage.getItem('auth_token') ?? ''),
  };
}

async function parseJson<T>(res: Response): Promise<T> {
  const contentType = res.headers.get('content-type') ?? '';
  const raw = await res.text();

  if (!res.ok) {
    throw new Error(`HTTP ${res.status}: ${raw || res.statusText}`);
  }

  if (!contentType.toLowerCase().includes('application/json')) {
    throw new Error(`Expected JSON response, got content-type=${contentType}. Body: ${raw.slice(0, 300)}`);
  }

  return JSON.parse(raw) as T;
}

export async function listWatchlists(): Promise<WlWatchlist[]> {
  const res = await apiFetch('/api/wave-lab/watchlists', { method: 'GET', headers: authHeaders() });
  return parseJson<WlWatchlist[]>(res);
}

export async function createWatchlist(name: string): Promise<WlWatchlist> {
  const res = await apiFetch('/api/wave-lab/watchlists', {
    method: 'POST', headers: authHeaders(), body: JSON.stringify({ name }),
  });
  return parseJson<WlWatchlist>(res);
}

export async function renameWatchlist(id: number, name: string): Promise<WlWatchlist> {
  const res = await apiFetch(`/api/wave-lab/watchlists/${id}`, {
    method: 'PUT', headers: authHeaders(), body: JSON.stringify({ name }),
  });
  return parseJson<WlWatchlist>(res);
}

export async function deleteWatchlist(id: number): Promise<void> {
  const res = await apiFetch(`/api/wave-lab/watchlists/${id}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
}

export async function listWatchlistItems(id: number): Promise<WlWatchlistItem[]> {
  const res = await apiFetch(`/api/wave-lab/watchlists/${id}/items`, { method: 'GET', headers: authHeaders() });
  return parseJson<WlWatchlistItem[]>(res);
}

export async function addWatchlistItem(id: number, symbol: string, exchange?: string): Promise<WlWatchlistItem> {
  const res = await apiFetch(`/api/wave-lab/watchlists/${id}/items`, {
    method: 'POST', headers: authHeaders(), body: JSON.stringify({ symbol, exchange: exchange || 'NSE' }),
  });
  return parseJson<WlWatchlistItem>(res);
}

export async function removeWatchlistItem(id: number, symbol: string): Promise<void> {
  const res = await apiFetch(`/api/wave-lab/watchlists/${id}/items/${encodeURIComponent(symbol)}`, {
    method: 'DELETE', headers: authHeaders(),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
}

export async function importCsv(id: number, csvContent: string): Promise<WlWatchlistItem[]> {
  const res = await apiFetch(`/api/wave-lab/watchlists/${id}/import-csv`, {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain', 'Authorization': 'Bearer ' + (localStorage.getItem('auth_token') ?? '') },
    body: csvContent,
  });
  return parseJson<WlWatchlistItem[]>(res);
}

export async function listOverlays(symbol: string, timeframe: string): Promise<WlOverlay[]> {
  const params = new URLSearchParams({ symbol, timeframe });
  const res = await apiFetch(`/api/wave-lab/overlays?${params}`, { method: 'GET', headers: authHeaders() });
  return parseJson<WlOverlay[]>(res);
}

export async function deleteOverlay(id: number): Promise<void> {
  const res = await apiFetch(`/api/wave-lab/overlays/${id}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
}

export async function clearOverlays(symbol: string, timeframe: string): Promise<void> {
  const params = new URLSearchParams({ symbol, timeframe });
  const res = await apiFetch(`/api/wave-lab/overlays?${params}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
}

export async function runAnalysis(symbol: string, timeframe: string): Promise<WlOverlay[]> {
  const res = await apiFetch(
    `/api/wave-lab/analyze?symbol=${encodeURIComponent(symbol)}&timeframe=${encodeURIComponent(timeframe)}`,
    { method: 'POST', headers: { 'Authorization': 'Bearer ' + (localStorage.getItem('auth_token') ?? '') } }
  );
  return parseJson<WlOverlay[]>(res);
}

export async function runAnalysisAll(watchlistId: number, timeframe: string): Promise<BatchResult> {
  const res = await apiFetch(
    `/api/wave-lab/analyze/batch?watchlistId=${watchlistId}&timeframe=${encodeURIComponent(timeframe)}`,
    { method: 'POST', headers: { 'Authorization': 'Bearer ' + (localStorage.getItem('auth_token') ?? '') } }
  );
  return parseJson<BatchResult>(res);
}

export async function analyzeTriangles(symbol: string, timeframe: string, candleLimit = 1000): Promise<TriangleRunResponse> {
  const res = await apiFetch('/api/wave-lab/elliott/triangles/analyze', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ symbol, timeframe, candleLimit }),
  });
  return parseJson<TriangleRunResponse>(res);
}
