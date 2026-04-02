import { apiFetch } from '../config/api';
import { withAuth } from '../utils/apiHelper';

export interface AiProviderDto {
  id: number;
  name: string;
  baseUrl: string;
  model: string;
  localProvider: boolean;
  active: boolean;
  hasApiKey: boolean;
}

export interface CreateProviderRequest {
  name: string;
  baseUrl: string;
  model: string;
  apiKey?: string;
  localProvider: boolean;
  makeActive: boolean;
}

export async function listAiProviders(): Promise<AiProviderDto[]> {
  const res = await apiFetch('/api/settings/ai-providers', withAuth());
  if (!res.ok) throw new Error(`Failed to list providers: ${res.status}`);
  return res.json();
}

export async function createAiProvider(req: CreateProviderRequest): Promise<AiProviderDto> {
  const res = await apiFetch('/api/settings/ai-providers', withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }));
  if (!res.ok) { const t = await res.text(); throw new Error(t || `Failed: ${res.status}`); }
  return res.json();
}

export async function updateAiProvider(id: number, req: Omit<CreateProviderRequest, 'makeActive'>): Promise<AiProviderDto> {
  const res = await apiFetch(`/api/settings/ai-providers/${id}`, withAuth({
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }));
  if (!res.ok) { const t = await res.text(); throw new Error(t || `Failed: ${res.status}`); }
  return res.json();
}

export async function deleteAiProvider(id: number): Promise<void> {
  const res = await apiFetch(`/api/settings/ai-providers/${id}`, withAuth({ method: 'DELETE' }));
  if (!res.ok) throw new Error(`Failed to delete: ${res.status}`);
}

export async function activateAiProvider(id: number): Promise<AiProviderDto> {
  const res = await apiFetch(`/api/settings/ai-providers/${id}/activate`, withAuth({ method: 'POST' }));
  if (!res.ok) throw new Error(`Failed to activate: ${res.status}`);
  return res.json();
}
