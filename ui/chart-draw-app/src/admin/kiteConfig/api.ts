import { apiFetch } from '../../config/api';
import { withAuth } from '../../utils/apiHelper';

export interface UserKiteConfigDto {
  id: number;
  platformUserId: number;
  platformUsername?: string;
  label: string;
  apiKey: string;
  kiteUserId?: string;
  connected: boolean;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateKiteConfigRequest {
  platformUserId: number;
  label: string;
  apiKey: string;
  apiSecret: string;
  kiteUserId?: string;
}

export interface UpdateKiteConfigRequest {
  label?: string;
  active?: boolean;
}

export async function listKiteConfigs(): Promise<UserKiteConfigDto[]> {
  const res = await apiFetch('/api/admin/kite-configs', withAuth());
  if (!res.ok) throw new Error(await res.text() || `Failed: ${res.status}`);
  return res.json();
}

export async function createKiteConfig(req: CreateKiteConfigRequest): Promise<UserKiteConfigDto> {
  const res = await apiFetch('/api/admin/kite-configs', withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }));
  if (!res.ok) throw new Error(await res.text() || `Failed: ${res.status}`);
  return res.json();
}

export async function updateKiteConfig(id: number, req: UpdateKiteConfigRequest): Promise<UserKiteConfigDto> {
  const res = await apiFetch(`/api/admin/kite-configs/${id}`, withAuth({
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }));
  if (!res.ok) throw new Error(await res.text() || `Failed: ${res.status}`);
  return res.json();
}

export async function deleteKiteConfig(id: number): Promise<void> {
  const res = await apiFetch(`/api/admin/kite-configs/${id}`, withAuth({ method: 'DELETE' }));
  if (!res.ok) throw new Error(await res.text() || `Failed: ${res.status}`);
}

export async function disconnectKiteConfig(id: number): Promise<void> {
  const res = await apiFetch(`/api/admin/kite-configs/${id}/disconnect`, withAuth({ method: 'POST' }));
  if (!res.ok) throw new Error(await res.text() || `Failed: ${res.status}`);
}

export function getKiteConnectUrl(id: number): string {
  // Returns the backend URL that redirects to Kite OAuth.
  // The Kite developer console must have redirect URI: {server}/kite-callback/config/{id}
  const base = (window as any).__API_BASE_URL__ || '';
  return `${base}/api/admin/kite-configs/${id}/connect`;
}
