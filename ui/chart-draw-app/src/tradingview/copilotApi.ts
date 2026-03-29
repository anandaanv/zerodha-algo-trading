import type {
  CopilotSkill, CopilotActiveTrade, CopilotObservation,
  TriggerAnalysisResponse, DashboardResponse, BoardResponse, AiAssistResponse,
  OrchestratorConfig, OrchestratorValidateResult,
  ScanResponse, ReasonResponse, ReasoningRequest, AdvancedElliottResult,
  VerifiedElliottResult,
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

export async function scanAnalysis(
  layoutId: number,
  symbol: string,
  drawingsJson?: string,
  timeframes?: string[],
  force = false,
): Promise<ScanResponse> {
  return request('/analysis/scan', {
    method: 'POST',
    body: JSON.stringify({ layoutId, symbol, drawingsJson, timeframes, force }),
  });
}

export async function reasonAnalysis(req: ReasoningRequest): Promise<ReasonResponse> {
  return request('/analysis/reason', {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export async function getObservations(investigationId: number): Promise<CopilotObservation[]> {
  return request(`/analysis/observations?investigationId=${investigationId}`);
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
  return request(`/copilot/skills/${id}/prompt`);
}

export async function orchestratorPrompt(): Promise<{ prompt: string }> {
  return request('/copilot/skills/orchestrator-prompt');
}

// ─── Orchestrator Config ───────────────────────────────────────────────────────

export async function getOrchestratorConfig(): Promise<OrchestratorConfig> {
  return request('/copilot/orchestrator');
}

export async function getOrchestratorDefault(): Promise<{ instructions: string }> {
  return request('/copilot/orchestrator/default');
}

export async function saveOrchestratorConfig(instructions: string): Promise<OrchestratorConfig> {
  return request('/copilot/orchestrator', {
    method: 'PUT',
    body: JSON.stringify({ instructions }),
  });
}

export async function validateOrchestratorConfig(instructions: string): Promise<OrchestratorValidateResult> {
  return request('/copilot/orchestrator/validate', {
    method: 'POST',
    body: JSON.stringify({ instructions }),
  });
}

export async function resetOrchestratorConfig(): Promise<OrchestratorConfig> {
  return request('/copilot/orchestrator', { method: 'DELETE' });
}

export async function aiAssistSkill(
  message: string,
  currentSkill: Partial<CopilotSkill>,
): Promise<AiAssistResponse> {
  return request('/copilot/skills/ai-assist', {
    method: 'POST',
    body: JSON.stringify({ message, currentSkill }),
  });
}

export async function testSkill(
  skillId: number,
  req: import('./copilotTypes').SkillTestRequest,
): Promise<import('./copilotTypes').SkillTestResult> {
  return request(`/copilot/skills/${skillId}/test`, {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export async function testOrchestrator(
  req: import('./copilotTypes').OrchestratorTestRequest,
): Promise<import('./copilotTypes').OrchestratorTestResult> {
  return request('/copilot/orchestrator/test', {
    method: 'POST',
    body: JSON.stringify(req),
  });
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

// ─── Advanced Elliott Analysis ────────────────────────────────────────────────

export async function runFullElliott(
  symbol: string,
  primaryTimeframe: string,
  timeframes: string,
  aiRecommend = false,
): Promise<AdvancedElliottResult> {
  const params = new URLSearchParams({ symbol, primaryTimeframe, timeframes });
  if (aiRecommend) params.set('aiRecommend', 'true');
  return request<AdvancedElliottResult>(`/analysis/full-elliott?${params}`, {
    method: 'POST',
  });
}

export async function runFullElliottVerified(
  symbol: string,
  primaryTimeframe: string,
  timeframes: string,
): Promise<VerifiedElliottResult> {
  const params = new URLSearchParams({ symbol, primaryTimeframe, timeframes });
  return request<VerifiedElliottResult>(`/analysis/full-elliott-verified?${params}`, {
    method: 'POST',
  });
}
