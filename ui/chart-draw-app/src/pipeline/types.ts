export interface TradeSignal {
  id: number;
  symbol: string;
  exchange: string;
  instrumentType: string;
  direction: 'LONG' | 'SHORT';
  patternType: string;
  confirmationType: string;
  entryPrice: number;
  stopLoss: number;
  target: number;
  rrRatio: number;
  mlScore: number;
  timeframe: string;
  signalTime: string;
  entryValidUntil: string;
  status: string; // WATCHING_ENTRY, ENTRY_PENDING, ACTIVE, EXIT_PENDING, COMPLETED, EXPIRED, FAILED, CANCELLED
  notes: string;
}

export interface TradeOrder {
  id: number;
  signal: { id: number };
  symbol: string;
  underlyingSymbol: string;
  segment: string;
  direction: 'LONG' | 'SHORT';
  quantity: number;
  lotSize: number;
  entryPrice: number;
  exitPrice: number | null;
  entryTime: string;
  exitTime: string | null;
  status: string; // OPEN, CLOSED, FAILED
  exitReason: string | null;
  realisedPnl: number | null;
  instrumentType: string;
  paperTrade: boolean;
}
