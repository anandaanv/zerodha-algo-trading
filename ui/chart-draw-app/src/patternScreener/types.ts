export interface PatternScreener {
  id: number;
  name: string;
  segments: string; // "EQ,OPT"
  watchingTf: string;
  confirmTf: string;
  scheduleCron: string;
  enabled: boolean;
  lastRunAt: string | null;
  nextRunAt: string | null;
  createdAt: string;
}

export interface PatternScreenerRequest {
  name: string;
  segments: string;
  watchingTf: string;
  confirmTf: string;
  scheduleCron: string;
  enabled: boolean;
}

export interface PatternScreenerRun {
  id: number;
  screenerId: number;
  status: string; // RUNNING, COMPLETED, FAILED
  totalSymbols: number;
  processedSymbols: number;
  signalsCreated: number;
  duplicatesSkipped: number;
  errorSummary: string | null;
  startedAt: string;
  completedAt: string | null;
}

export interface PatternScreenerRunResult {
  id: number;
  runId: number;
  screenerId: number;
  symbol: string;
  status: string; // NO_PATTERN, PATTERN_FOUND, SIGNAL_CREATED, DUPLICATE, FILTERED, ERROR
  patternsFound: string; // JSON string
  signalId: number | null;
  errorMessage: string | null;
  processingMs: number;
  scannedAt: string;
}
