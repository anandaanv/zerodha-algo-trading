import { apiFetch } from '../../config/api';
import { withAuth } from '../../utils/apiHelper';

export interface ScanGroup {
  id: number;
  name: string;
  maxScansPerHour: number | null;
  screenerAccess: boolean;
}

export interface ScanGroupMember {
  id: number;
  groupId: number;
  userId: number;
}

export async function listGroups(): Promise<ScanGroup[]> {
  const res = await apiFetch('/api/scan/groups', withAuth());
  if (!res.ok) throw new Error(await res.text() || `Failed: ${res.status}`);
  return res.json();
}

export async function createGroup(name: string, maxScansPerHour: number | null): Promise<ScanGroup> {
  const res = await apiFetch('/api/scan/groups', withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, maxScansPerHour }),
  }));
  if (!res.ok) throw new Error(await res.text() || `Failed: ${res.status}`);
  return res.json();
}

export async function updateGroup(id: number, patch: { screenerAccess?: boolean; maxScansPerHour?: number | null }): Promise<ScanGroup> {
  const res = await apiFetch(`/api/scan/groups/${id}`, withAuth({
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }));
  if (!res.ok) throw new Error(await res.text() || `Failed: ${res.status}`);
  return res.json();
}

export async function getGroupMembers(groupId: number): Promise<ScanGroupMember[]> {
  const res = await apiFetch(`/api/scan/groups/${groupId}/members`, withAuth());
  if (!res.ok) throw new Error(await res.text() || `Failed: ${res.status}`);
  return res.json();
}

export async function addMember(groupId: number, userId: number): Promise<void> {
  const res = await apiFetch(`/api/scan/groups/${groupId}/members`, withAuth({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId }),
  }));
  if (!res.ok) throw new Error(await res.text() || `Failed: ${res.status}`);
}

export async function removeMember(groupId: number, userId: number): Promise<void> {
  const res = await apiFetch(`/api/scan/groups/${groupId}/members/${userId}`, withAuth({ method: 'DELETE' }));
  if (!res.ok) throw new Error(await res.text() || `Failed: ${res.status}`);
}
