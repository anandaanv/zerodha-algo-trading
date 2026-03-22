// ─── Core Entity Types ────────────────────────────────────────────────────────

export type HypothesisState =
  | 'WATCHING' | 'BUILDING' | 'CONFIRMED' | 'TRADE_ACTIVE' | 'INVALIDATED' | 'EXPIRED';

export type RelationshipType = 'CONFLICTING' | 'SEQUENTIAL' | 'INDEPENDENT' | 'REINFORCING';

export interface CopilotHypothesis {
  id: number;
  investigationId: number;
  label: string;
  pattern: string;
  state: HypothesisState;
  stage: string;
  direction: string;
  waveContext: string;
  confidenceLayers: string;
  invalidationConditions: string;
  anticipatoryTrade: string;
  confirmationTrade: string;
  invalidationReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CopilotHypothesisRelationship {
  id: number;
  primaryHypothesisId: number;
  relatedHypothesisId: number;
  relationshipType: RelationshipType;
  notes: string;
}

export interface CopilotActiveTrade {
  id: number;
  investigationId: number;
  hypothesisId: number;
  entryType: 'ANTICIPATORY' | 'CONFIRMATION';
  status: string;
  entryPrice: number | null;
  closePrice: number | null;
  size: number | null;
  sl: number | null;
  tp: number | null;
  isOverrideTrade: boolean;
  systemObjection: string | null;
  overrideReason: string | null;
  systemConcernMaterialized: boolean;
  outcome: string;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CopilotAnomalyFlag {
  id: number;
  investigationId: number;
  hypothesisId: number | null;
  flagText: string;
  severity: string;
  acknowledged: boolean;
  actionTaken: 'ACCEPT' | 'OVERRIDE' | 'DISMISS' | null;
  expertNotes: string | null;
  createdAt: string;
}

export interface CopilotInvestigation {
  id: number;
  layoutId: number;
  userId: number;
  symbol: string;
  timeframes: string;
  status: 'ACTIVE' | 'EXPIRED' | 'SUPERSEDED';
  waveCountConfirmed: boolean | null;
  waveCountSource: string | null;
  expiresAt: string;
  createdAt: string;
}

export interface CopilotSkill {
  id: number;
  skillKey: string;
  name: string;
  description: string;
  category: string;
  identificationRules: string;
  confidenceRules: string;
  entryRules: string;
  invalidationRules: string;
  waveContextRules: string;
  managementRules: string;
  crossVerificationRules: string;
  isSystemSeed: boolean;
  isActive: boolean;
}

// ─── API Response Shapes ──────────────────────────────────────────────────────

export interface TriggerAnalysisResponse {
  investigationId: number;
  status: 'created' | 'existing';
  message?: string;
  hypotheses: CopilotHypothesis[];
  flags: CopilotAnomalyFlag[];
}

export interface DashboardResponse {
  activeTrades: CopilotActiveTrade[];
  overrideTrades: CopilotActiveTrade[];
  hypotheses: CopilotHypothesis[];
  unacknowledgedFlags: CopilotAnomalyFlag[];
}

export interface BoardResponse {
  hypotheses: CopilotHypothesis[];
  unacknowledgedFlags: CopilotAnomalyFlag[];
}
