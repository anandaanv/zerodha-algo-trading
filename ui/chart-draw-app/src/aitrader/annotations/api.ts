import { getApiUrl } from '../../config/api';
import { withAuth } from '../../utils/apiHelper';
import type {
  DrawingAnnotation, JournalNote,
  SaveAnnotationRequest, SaveJournalNoteRequest,
} from './types';

const BASE = '/api/ai-trader/annotations';

async function jsonOrThrow<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error((await res.text()) || `HTTP ${res.status}`);
  if (res.status === 204) return undefined as unknown as T;
  return res.json() as Promise<T>;
}

// ── Drawing annotations ───────────────────────────────────────────────

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

// ── Journal notes ─────────────────────────────────────────────────────

export async function listJournalNotes(symbol: string): Promise<JournalNote[]> {
  const qs = new URLSearchParams({ symbol });
  const res = await fetch(getApiUrl(`${BASE}/journal?${qs.toString()}`).toString(), withAuth());
  return jsonOrThrow(res);
}

export async function addJournalNote(req: SaveJournalNoteRequest): Promise<JournalNote> {
  const res = await fetch(getApiUrl(`${BASE}/journal`).toString(), withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }));
  return jsonOrThrow(res);
}

export async function deleteJournalNote(id: number): Promise<void> {
  const res = await fetch(getApiUrl(`${BASE}/journal/${id}`).toString(), withAuth({ method: 'DELETE' }));
  if (!res.ok) throw new Error((await res.text()) || `HTTP ${res.status}`);
}
