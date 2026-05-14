import { getApiUrl } from '../../config/api';
import { withAuth } from '../../utils/apiHelper';
import type {
  DrawingAnnotation, SymbolThesis,
  SaveAnnotationRequest, SaveThesisRequest,
} from './types';

const BASE = '/api/ai-trader/annotations';

async function jsonOrThrow<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error((await res.text()) || `HTTP ${res.status}`);
  if (res.status === 204) return undefined as unknown as T;
  return res.json() as Promise<T>;
}

export async function listAnnotations(symbol: string, tabUuid?: string): Promise<DrawingAnnotation[]> {
  const qs = new URLSearchParams({ symbol });
  if (tabUuid) qs.set('tabUuid', tabUuid);
  const res = await fetch(getApiUrl(`${BASE}?${qs.toString()}`).toString(), withAuth());
  return jsonOrThrow(res);
}

export async function saveAnnotation(req: SaveAnnotationRequest): Promise<DrawingAnnotation> {
  const res = await fetch(getApiUrl(BASE).toString(), withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }));
  return jsonOrThrow(res);
}

export async function deleteAnnotation(id: number): Promise<void> {
  const res = await fetch(getApiUrl(`${BASE}/${id}`).toString(), withAuth({ method: 'DELETE' }));
  if (!res.ok) throw new Error((await res.text()) || `HTTP ${res.status}`);
}

export async function getThesis(symbol: string, tabUuid: string): Promise<SymbolThesis | null> {
  const qs = new URLSearchParams({ symbol, tabUuid });
  const res = await fetch(getApiUrl(`${BASE}/thesis?${qs.toString()}`).toString(), withAuth());
  if (res.status === 204) return null;
  return jsonOrThrow(res);
}

export async function saveThesis(req: SaveThesisRequest): Promise<SymbolThesis> {
  const res = await fetch(getApiUrl(`${BASE}/thesis`).toString(), withAuth({
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }));
  return jsonOrThrow(res);
}
