export type SuggestionState =
  | 'PROPOSED'
  | 'ANTICIPATORY'
  | 'ACTIVE'
  | 'SUCCESSFUL'
  | 'FAILED'
  | 'REJECTED';

export interface ElliottScreener {
  id: number;
  userId: number;
  name: string;
  symbols: string;
  timeframes: string;
  primaryTimeframe: string | null;
  scheduleCron: string;
  enabled: boolean;
  nextRunAt: string | null;
  lastRunAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ElliottScreenerRun {
  id: number;
  screenerId: number;
  status: string;
  totalSymbols: number;
  processedSymbols: number;
  suggestionsCreated: number;
  duplicatesSkipped: number;
  errorSummary: string | null;
  startedAt: string;
  completedAt: string | null;
  createdAt: string;
}

export interface ElliottTradeSuggestion {
  id: number;
  screenerId: number;
  runId: number | null;
  userId: number;
  symbol: string;
  direction: 'LONG' | 'SHORT';
  state: SuggestionState;
  hypothesisLabel: string | null;
  waveContext: string | null;
  pattern: string | null;
  currentStage: string | null;
  entryZone: string | null;
  stopLoss: string | null;
  target1: string | null;
  triggerDescription: string | null;
  reasoning: string | null;
  confidenceLayers: Record<string, string> | null;
  invalidationConditions: string[] | null;
  anomalyFlags: string[] | null;
  userNotes: string | null;
  primaryTimeframe: string | null;
  allTimeframes: string | null;
  proposedAt: string | null;
  acceptedAt: string | null;
  activatedAt: string | null;
  closedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateScreenerRequest {
  name: string;
  symbols: string;
  timeframes: string;
  primaryTimeframe?: string;
  scheduleCron: string;
}
