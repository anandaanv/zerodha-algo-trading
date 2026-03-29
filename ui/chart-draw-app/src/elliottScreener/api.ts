import { apiFetch } from "../config/api";
import { withAuth } from "../utils/apiHelper";
import { ElliottScreener, ElliottScreenerRun, ElliottTradeSuggestion, CreateScreenerRequest, SuggestionState } from './types';

export async function listScreeners(): Promise<ElliottScreener[]> {
  const res = await apiFetch('/api/elliott-screener', withAuth());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function getScreener(id: number): Promise<ElliottScreener> {
  const res = await apiFetch(`/api/elliott-screener/${id}`, withAuth());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function createScreener(req: CreateScreenerRequest): Promise<ElliottScreener> {
  const res = await apiFetch('/api/elliott-screener', withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function updateScreener(id: number, req: CreateScreenerRequest): Promise<ElliottScreener> {
  const res = await apiFetch(`/api/elliott-screener/${id}`, withAuth({
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function deleteScreener(id: number): Promise<void> {
  const res = await apiFetch(`/api/elliott-screener/${id}`, withAuth({ method: 'DELETE' }));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
}

export async function triggerRun(id: number): Promise<ElliottScreenerRun> {
  const res = await apiFetch(`/api/elliott-screener/${id}/run`, withAuth({ method: 'POST' }));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function getRunHistory(id: number): Promise<ElliottScreenerRun[]> {
  const res = await apiFetch(`/api/elliott-screener/${id}/runs`, withAuth());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function listSuggestions(states?: SuggestionState[]): Promise<ElliottTradeSuggestion[]> {
  const query = states && states.length > 0 ? `?states=${states.join(',')}` : '';
  const res = await apiFetch(`/api/elliott-suggestions${query}`, withAuth());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function getSuggestion(id: number): Promise<ElliottTradeSuggestion> {
  const res = await apiFetch(`/api/elliott-suggestions/${id}`, withAuth());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function getSuggestionsForScreener(screenerId: number): Promise<ElliottTradeSuggestion[]> {
  const res = await apiFetch(`/api/elliott-suggestions/screener/${screenerId}`, withAuth());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function acceptSuggestion(id: number, notes?: string): Promise<ElliottTradeSuggestion> {
  const res = await apiFetch(`/api/elliott-suggestions/${id}/accept`, withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ notes }),
  }));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function activateSuggestion(id: number, notes?: string): Promise<ElliottTradeSuggestion> {
  const res = await apiFetch(`/api/elliott-suggestions/${id}/activate`, withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ notes }),
  }));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function closeSuggestion(id: number, successful: boolean, notes?: string): Promise<ElliottTradeSuggestion> {
  const res = await apiFetch(`/api/elliott-suggestions/${id}/close`, withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ successful, notes }),
  }));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function rejectSuggestion(id: number, notes?: string): Promise<ElliottTradeSuggestion> {
  const res = await apiFetch(`/api/elliott-suggestions/${id}/reject`, withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ notes }),
  }));
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export interface SuggestionChartLayoutDto {
  id: number;
  suggestionId: number;
  timeframe: string;
  tabOrder: number;
  overlays: Record<string, any[]>;
}

export async function fetchSuggestionChartLayouts(suggestionId: number): Promise<SuggestionChartLayoutDto[]> {
  const res = await apiFetch(`/api/elliott-screener/suggestions/${suggestionId}/chart-layouts`, withAuth());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Failed to fetch chart layouts: ${res.status}`);
  }
  return res.json();
}
