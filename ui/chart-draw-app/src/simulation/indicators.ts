/**
 * Technical indicators: EMA, Bollinger Bands, MACD, RSI
 * All functions return arrays with NaN padding for warmup periods to maintain index alignment
 */

export function ema(values: number[], period: number): number[] {
  if (values.length === 0) return [];

  const result: number[] = new Array(values.length).fill(NaN);
  const k = 2 / (period + 1);

  // Initialize: first valid EMA is simple average of first `period` values
  let sum = 0;
  for (let i = 0; i < period && i < values.length; i++) {
    sum += values[i];
  }
  if (period > values.length) {
    // Not enough data
    return result;
  }

  let emaValue = sum / period;
  result[period - 1] = emaValue;

  // EMA formula: EMA = price * k + EMA_prev * (1 - k)
  for (let i = period; i < values.length; i++) {
    emaValue = values[i] * k + emaValue * (1 - k);
    result[i] = emaValue;
  }

  return result;
}

export interface BollingerBandsResult {
  upper: number[];
  middle: number[];
  lower: number[];
}

export function bollingerBands(
  values: number[],
  period: number = 20,
  stdMult: number = 2
): BollingerBandsResult {
  const upper: number[] = new Array(values.length).fill(NaN);
  const middle: number[] = new Array(values.length).fill(NaN);
  const lower: number[] = new Array(values.length).fill(NaN);

  if (values.length < period) {
    return { upper, middle, lower };
  }

  // Calculate SMA and standard deviation
  for (let i = period - 1; i < values.length; i++) {
    let sum = 0;
    for (let j = i - period + 1; j <= i; j++) {
      sum += values[j];
    }
    const sma = sum / period;
    middle[i] = sma;

    // Calculate standard deviation
    let sqSum = 0;
    for (let j = i - period + 1; j <= i; j++) {
      sqSum += Math.pow(values[j] - sma, 2);
    }
    const std = Math.sqrt(sqSum / period);

    upper[i] = sma + stdMult * std;
    lower[i] = sma - stdMult * std;
  }

  return { upper, middle, lower };
}

export interface MACDResult {
  macd: number[];
  signal: number[];
  histogram: number[];
}

export function macd(
  values: number[],
  fast: number = 12,
  slow: number = 26,
  signalPeriod: number = 9
): MACDResult {
  const macdLine: number[] = new Array(values.length).fill(NaN);
  const signalLine: number[] = new Array(values.length).fill(NaN);
  const histogram: number[] = new Array(values.length).fill(NaN);

  if (values.length < slow) {
    return { macd: macdLine, signal: signalLine, histogram };
  }

  // Compute fast and slow EMAs
  const fastEMA = ema(values, fast);
  const slowEMA = ema(values, slow);

  // MACD = fast EMA - slow EMA
  for (let i = 0; i < values.length; i++) {
    if (!isNaN(fastEMA[i]) && !isNaN(slowEMA[i])) {
      macdLine[i] = fastEMA[i] - slowEMA[i];
    }
  }

  // Signal line is EMA of MACD
  const signalEMA = ema(
    macdLine.map((v) => (isNaN(v) ? 0 : v)),
    signalPeriod
  );
  for (let i = 0; i < values.length; i++) {
    if (!isNaN(macdLine[i]) && !isNaN(signalEMA[i])) {
      signalLine[i] = signalEMA[i];
      histogram[i] = macdLine[i] - signalLine[i];
    }
  }

  return { macd: macdLine, signal: signalLine, histogram };
}

export function rsi(values: number[], period: number = 14): number[] {
  const result: number[] = new Array(values.length).fill(NaN);

  if (values.length < period + 1) {
    return result;
  }

  const changes: number[] = [];
  for (let i = 1; i < values.length; i++) {
    changes.push(values[i] - values[i - 1]);
  }

  // First RSI: average gains and losses over period
  let sumGains = 0;
  let sumLosses = 0;
  for (let i = 0; i < period; i++) {
    const change = changes[i];
    if (change > 0) sumGains += change;
    else sumLosses -= change;
  }

  let avgGain = sumGains / period;
  let avgLoss = sumLosses / period;
  let rs = avgLoss === 0 ? 0 : avgGain / avgLoss;
  result[period] = 100 - 100 / (1 + rs);

  // Subsequent RSI: smoothed averages
  for (let i = period + 1; i < values.length; i++) {
    const change = changes[i - 1];
    if (change > 0) {
      avgGain = (avgGain * (period - 1) + change) / period;
      avgLoss = (avgLoss * (period - 1)) / period;
    } else {
      avgGain = (avgGain * (period - 1)) / period;
      avgLoss = (avgLoss * (period - 1) - change) / period;
    }

    rs = avgLoss === 0 ? 0 : avgGain / avgLoss;
    result[i] = 100 - 100 / (1 + rs);
  }

  return result;
}

export function stochRsi(
  values: number[],
  rsiPeriod: number = 14,
  stochPeriod: number = 14
): number[] {
  const result: number[] = new Array(values.length).fill(NaN);

  // Compute RSI first
  const rsiValues = rsi(values, rsiPeriod);

  // Not enough data for StochRSI
  if (rsiValues.length < stochPeriod + rsiPeriod - 1) {
    return result;
  }

  // For each bar, compute StochRSI using RSI values over stochPeriod
  for (let i = stochPeriod + rsiPeriod - 2; i < rsiValues.length; i++) {
    // Get min and max RSI over the stochPeriod
    let minRsi = Infinity;
    let maxRsi = -Infinity;

    for (let j = i - stochPeriod + 1; j <= i; j++) {
      if (!isNaN(rsiValues[j])) {
        minRsi = Math.min(minRsi, rsiValues[j]);
        maxRsi = Math.max(maxRsi, rsiValues[j]);
      }
    }

    // StochRSI = (RSI - min RSI) / (max RSI - min RSI) * 100
    const currentRsi = rsiValues[i];
    if (!isNaN(currentRsi) && maxRsi !== minRsi) {
      result[i] = ((currentRsi - minRsi) / (maxRsi - minRsi)) * 100;
    } else if (!isNaN(currentRsi)) {
      // If max == min, StochRSI is 50 (neutral)
      result[i] = 50;
    }
  }

  return result;
}
