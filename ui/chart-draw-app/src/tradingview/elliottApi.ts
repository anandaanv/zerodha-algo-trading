const BASE = '/api';

async function request<T>(path: string, opts?: RequestInit): Promise<T> {
  const token = localStorage.getItem('auth_token');
  const res = await fetch(`${BASE}${path}`, {
    ...opts,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...opts?.headers,
    },
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status}: ${text}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}

// ─── Elliott Analysis ─────────────────────────────────────────────────────────

export async function runFullElliott(
  symbol: string,
  primaryTimeframe: string,
  timeframes: string,
  aiRecommend = false,
): Promise<any> {
  const params = new URLSearchParams({ symbol, primaryTimeframe, timeframes });
  if (aiRecommend) params.set('aiRecommend', 'true');
  return request<any>(`/analysis/full-elliott?${params}`, { method: 'POST' });
}

export async function triggerAnalysis(
  layoutId: number,
  symbol: string,
  drawingsJson?: string,
  timeframes?: string[],
  force = false,
): Promise<any> {
  return request('/analysis/trigger', {
    method: 'POST',
    body: JSON.stringify({ layoutId, symbol, drawingsJson, timeframes, force }),
  });
}

// ─── Watchlist API ────────────────────────────────────────────────────────────

export async function listWatchlists(): Promise<any[]> {
  return request('/watchlists');
}

export async function createWatchlist(name: string): Promise<any> {
  return request('/watchlists', { method: 'POST', body: JSON.stringify({ name }) });
}

export async function deleteWatchlist(id: number): Promise<void> {
  return request(`/watchlists/${id}`, { method: 'DELETE' });
}

export async function addSymbolToWatchlist(watchlistId: number, symbol: string): Promise<void> {
  return request(`/watchlists/${watchlistId}/symbols`, {
    method: 'POST',
    body: JSON.stringify({ symbol }),
  });
}

export async function removeSymbolFromWatchlist(watchlistId: number, symbol: string): Promise<void> {
  return request(`/watchlists/${watchlistId}/symbols/${encodeURIComponent(symbol)}`, {
    method: 'DELETE',
  });
}

export async function importCsvToWatchlist(
  watchlistId: number,
  file: File,
): Promise<{ imported: number }> {
  const token = localStorage.getItem('auth_token');
  const formData = new FormData();
  formData.append('file', file);
  const res = await fetch(`${BASE}/watchlists/${watchlistId}/import-csv`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: formData,
  });
  if (!res.ok) throw new Error(`${res.status}: ${await res.text()}`);
  return res.json();
}
