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
  stageDetection: string;
  entryRules: string;
  indicatorRules: string;
  invalidationRules: string;
  ambiguityQuestions: string;
  crossVerificationRules: string;
  isSystemSeed: boolean;
  isActive: boolean;
}

export interface AiAssistResponse {
  reply: string;
  suggestedFields?: Record<string, string>;
}

export interface OrchestratorConfig {
  instructions: string;
  isCustomized: boolean;
}

export interface OrchestratorValidateResult {
  valid: boolean;
  issues: string[];
  sampleResponse: string | null;
}

export interface SkillTestRequest {
  symbol: string;
  timeframe: string;
  patternPresent: boolean;
  description: string;
}

export interface SkillTestResult {
  matched: boolean;
  verdict: string;
  analysis: string;
  failedRules: string[];
  suggestedChanges: Record<string, string>;
}

export interface OrchestratorTestRequest {
  symbol: string;
  timeframe: string;
  description: string;
  expectedSkills: string[];
}

export interface OrchestratorTestResult {
  selectedSkills: string[];
  correct: boolean;
  verdict: string;
  analysis: string;
  suggestedChanges: string;
}

// ─── Observation Types (Phase 1: Scan) ────────────────────────────────────────

export interface KeyLevel {
  price: number;
  label: string;
}

export interface DrawingPoint {
  time: number;  // unix timestamp in seconds
  price: number;
  label: string;
}

export interface CopilotObservation {
  id: number;
  investigationId: number;
  skillKey: string;
  patternDetected: boolean;
  patternType: string | null;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW' | null;
  structuralDetails: string | null;
  stage: string | null;
  keyLevels: string | null;     // JSON string of KeyLevel[]
  drawingPoints: string | null; // JSON string of DrawingPoint[]
  drawingType: string | null;
  timeframe: string | null;
  contradictions: string | null; // JSON string of string[]
  reasoning: string | null;
  createdAt: string;
}

export interface ReasoningRequest {
  investigationId: number;
  observationIds?: number[];
  drawingsJson?: string;
  scenarioText?: string;
  priorHypothesisIds?: number[];
  reasoningSkillKeys?: string[];
}

// ─── API Response Shapes ──────────────────────────────────────────────────────

export interface ScanResponse {
  investigationId: number;
  status: 'scanned';
  observations: CopilotObservation[];
  warning?: string;
}

export interface ReasonResponse {
  investigationId: number;
  status: 'reasoned';
  hypotheses: CopilotHypothesis[];
  flags: CopilotAnomalyFlag[];
  observations: CopilotObservation[];
  warning?: string;
}

export interface TriggerAnalysisResponse {
  investigationId: number;
  status: 'created' | 'existing';
  message?: string;
  warning?: string;
  hypotheses: CopilotHypothesis[];
  flags: CopilotAnomalyFlag[];
  observations?: CopilotObservation[];
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
