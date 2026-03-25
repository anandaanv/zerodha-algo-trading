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

// ─── Advanced Elliott Analysis Types ─────────────────────────────────────────

export type ScenarioStatus =
  | 'LEADING'
  | 'ACTIVE_ALTERNATE'
  | 'WEAK_ALTERNATE'
  | 'AWAITING_TRIGGER'
  | 'INVALIDATED'
  | 'COMPLETED';

export type ElliottTriggerType =
  | 'HAMMER'
  | 'SHOOTING_STAR'
  | 'BULLISH_ENGULFING'
  | 'BEARISH_ENGULFING'
  | 'LONG_LOWER_WICK_REJECTION'
  | 'LONG_UPPER_WICK_REJECTION'
  | 'NONE';

export interface ElliottConfluenceZone {
  id: string;
  lowerPrice: number;
  upperPrice: number;
  midPrice: number;
  factorCount: number;
  factorDiversity: number;
  score: number;
  zoneType: string;
  explanation: string[];
}

export interface ElliottEntryCandidate {
  id: string;
  symbol: string;
  scenarioId: string;
  hypothesisId: string;
  bullish: boolean;
  entryStyle: string;
  triggerType: ElliottTriggerType;
  entryPrice: number;
  stopLoss: number;
  target1: number;
  target2: number;
  riskRewardRatio: number;
  rationale: string[];
}

export interface ElliottScenarioTransition {
  scenarioId: string;
  fromStatus: ScenarioStatus | null;
  toStatus: ScenarioStatus;
  timestamp: number;
  reason: string;
}

export interface ElliottHypothesisSnapshot {
  symbol: string;
  primaryTimeframe: string;
  snapshotTime: number;
  transitions: ElliottScenarioTransition[];
  relabelCount: number;
  leadingScenarioId: string | null;
}

export interface ElliottScoredScenario {
  status: ScenarioStatus;
  totalScore: number;
  statusReasons: string[];
  scenario: {
    id: string;
    direction: string;
    scenarioInvalidation: number;
    hypotheses: Array<{
      id: string;
      currentPositionDescription: string;
      nextMajorMoveUp: boolean;
      totalScore: number;
      invalidationLevel: number;
      primaryTarget: { level: number; ratio: string; confidence: number } | null;
    }>;
  };
}

export interface AdvancedElliottResult {
  confluenceZones: ElliottConfluenceZone[];
  scoredScenarios: ElliottScoredScenario[];
  entryCandidates: ElliottEntryCandidate[];
  hypothesisSnapshot: ElliottHypothesisSnapshot | null;
  promptSummary: string;
}

// ─── Second-Pass Filtering + AI Verification ─────────────────────────────────

export interface FamilyScore {
  structuralStrength: number;
  confluenceStrength: number;
  momentumAlignment: number;
  ambiguityPenalty: number;
  contradictionPenalty: number;
  tradeUtility: number;
  triggerReadiness: number;
  finalRankScore: number;
}

export interface ScenarioFamilyCandidate {
  id: string;
  symbol: string;
  anchorTimeframe: string;
  familyType: string;
  directionalBias: 'UP' | 'DOWN' | 'NEUTRAL';
  supportingStructures: string[];
  supportingPatterns: string[];
  contradictingPatterns: string[];
  primaryInvalidationLevel: number;
  confirmationLevel: number;
  decisionZoneNearby: boolean;
  triggerEligible: boolean;
  tradableNow: boolean;
  status: ScenarioStatus;
  score: FamilyScore;
  explanation: string[];
  reasonCodes: string[];
}

export interface ScenarioConflictSet {
  symbol: string;
  anchorTimeframe: string;
  bullishFamilies: ScenarioFamilyCandidate[];
  bearishFamilies: ScenarioFamilyCandidate[];
  neutralFamilies: ScenarioFamilyCandidate[];
  dominantConflictMode: string;
  explanation: string[];
}

export interface HumanResearchSummary {
  symbol: string;
  anchorTimeframe: string;
  marketStateSummary: string;
  leadingScenarioSummary: string[];
  alternateScenarioSummary: string[];
  actionHandlingNotes: string[];
  invalidationNotes: string[];
}

export interface FilteredScenarioSet {
  symbol: string;
  anchorTimeframe: string;
  leadingScenario: ScenarioFamilyCandidate | null;
  activeAlternates: ScenarioFamilyCandidate[];
  weakAlternates: ScenarioFamilyCandidate[];
  invalidatedFamilies: ScenarioFamilyCandidate[];
  conflictSet: ScenarioConflictSet | null;
  humanSummary: HumanResearchSummary | null;
}

export interface VerifiedElliottResult {
  filteredScenarioSet: FilteredScenarioSet;
  aiRawResponse: string | null;
  aiConfirmed: boolean;
  aiReasoning: string;
  aiConfidence: number;
}
