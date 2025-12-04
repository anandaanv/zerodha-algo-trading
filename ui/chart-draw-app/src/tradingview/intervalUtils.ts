// Map your timeframe format to TradingView interval format
export function mapTimeframeToInterval(timeframe: string | null): string {
  if (!timeframe) return '60';

  const mapping: Record<string, string> = {
    '1m': '1',
    '3m': '3',
    '5m': '5',
    '15m': '15',
    '30m': '30',
    '1h': '60',
    '2h': '120',
    '1d': '1D',
    '1w': '1W',
    '1M': '1M',
  };

  return mapping[timeframe.toLowerCase()] || '60';
}

// Convert TradingView interval back to period format for API
export function intervalToPeriod(interval: string): string {
  const mapping: Record<string, string> = {
    '1': '1m',
    '3': '3m',
    '5': '5m',
    '15': '15m',
    '30': '30m',
    '60': '1h',
    '120': '2h',
    '1D': '1d',
    '1W': '1w',
    '1M': '1M',
  };
  return mapping[interval] || interval;
}
