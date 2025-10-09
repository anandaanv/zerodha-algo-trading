export type Trade = {
  id: number | string;
  script: string;
  timeframe: string; // e.g., "H1", "D1", etc.
  side: string; // e.g., "BUY" | "SELL"
  entry?: number | null;
  target?: number | null;
  stoploss?: number | null;
  timeTriggered?: string; // ISO
  open: boolean;
  logs?: string | null;
  runId?: number | null;
  createdAt?: string; // ISO
  updatedAt?: string; // ISO

  // Optional fields depending on backend shape
  ai?: {
    entry?: number | null;
    target?: number | null;
    stoploss?: number | null;
    rationale?: string | null;
  } | null;

  screenerDebug?: unknown;
};
