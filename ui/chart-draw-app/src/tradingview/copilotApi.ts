import type {
  CopilotSkill, CopilotActiveTrade,
  TriggerAnalysisResponse, DashboardResponse, BoardResponse,
} from './copilotTypes';

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

// ─── Analysis ─────────────────────────────────────────────────────────────────

export async function triggerAnalysis(
  layoutId: number,
  symbol: string,
  drawingsJson?: string,
  timeframes?: string[],
  force = false,
): Promise<TriggerAnalysisResponse> {
  return request('/analysis/trigger', {
    method: 'POST',
    body: JSON.stringify({ layoutId, symbol, drawingsJson, timeframes, force }),
  });
}

// ─── Hypotheses ───────────────────────────────────────────────────────────────

export async function getHypothesisBoard(investigationId: number): Promise<BoardResponse> {
  return request(`/hypotheses/board?investigationId=${investigationId}`);
}

export async function confirmHypothesis(
  id: number,
  entryType: string,
  isOverride: boolean,
  overrideReason?: string,
  systemObjection?: string,
): Promise<any> {
  return request(`/hypotheses/${id}/confirm`, {
    method: 'POST',
    body: JSON.stringify({ entryType, override: isOverride, overrideReason, systemObjection }),
  });
}

export async function dismissHypothesis(id: number, reason?: string): Promise<any> {
  return request(`/hypotheses/${id}/dismiss`, {
    method: 'POST',
    body: JSON.stringify({ reason: reason ?? 'Dismissed by expert' }),
  });
}

export async function acknowledgeFlag(
  flagId: number,
  action: string,
  notes?: string,
): Promise<any> {
  return request(`/hypotheses/flags/${flagId}/acknowledge`, {
    method: 'POST',
    body: JSON.stringify({ action, notes }),
  });
}

// ─── Trades ───────────────────────────────────────────────────────────────────

export async function getTradeDashboard(investigationId: number): Promise<DashboardResponse> {
  return request(`/trades/dashboard?investigationId=${investigationId}`);
}

export async function openTrade(
  id: number,
  entryPrice: number,
  size?: number,
): Promise<CopilotActiveTrade> {
  return request(`/trades/${id}/open`, {
    method: 'POST',
    body: JSON.stringify({ entryPrice, size: size ?? 1 }),
  });
}

export async function closeTrade(
  id: number,
  closePrice: number,
  outcome: string,
  notes?: string,
): Promise<CopilotActiveTrade> {
  return request(`/trades/${id}/close`, {
    method: 'POST',
    body: JSON.stringify({ closePrice, outcome, notes }),
  });
}

// ─── Skills ───────────────────────────────────────────────────────────────────

export async function getSkills(): Promise<CopilotSkill[]> {
  return request('/copilot/skills');
}

export async function getSkill(id: number): Promise<CopilotSkill> {
  return request(`/copilot/skills/${id}`);
}

export async function createSkill(skill: Partial<CopilotSkill>): Promise<CopilotSkill> {
  return request('/copilot/skills', { method: 'POST', body: JSON.stringify(skill) });
}

export async function updateSkill(id: number, skill: Partial<CopilotSkill>): Promise<CopilotSkill> {
  return request(`/copilot/skills/${id}`, { method: 'PUT', body: JSON.stringify(skill) });
}

export async function deleteSkill(id: number): Promise<void> {
  return request(`/copilot/skills/${id}`, { method: 'DELETE' });
}

export async function seedDemoSkills(): Promise<any> {
  return request('/copilot/skills/seed', { method: 'POST' });
}

export async function previewSkillPrompt(id: number): Promise<{ prompt: string }> {
  return request(`/copilot/skills/${id}/preview`);
}

// ─── OpenAI Credentials ───────────────────────────────────────────────────────

export async function saveOpenAiKey(apiKey: string, model?: string): Promise<void> {
  return request('/copilot/credentials', {
    method: 'POST',
    body: JSON.stringify({ apiKey, model }),
  });
}

export async function getOpenAiKeyStatus(): Promise<{ configured: boolean; model?: string; baseUrl?: string }> {
  return request('/copilot/credentials/status');
}

export async function deleteOpenAiKey(): Promise<void> {
  return request('/copilot/credentials', { method: 'DELETE' });
}
