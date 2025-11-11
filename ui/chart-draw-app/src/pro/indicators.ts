import { type IChartApi, type CandlestickData, type ISeriesApi } from "lightweight-charts";

type BarRow = {
  time?: number;
  timestamp?: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

// Indicator calculation helper functions
export function calculateEMA(data: number[], period: number): number[] {
  const ema: number[] = [];
  const k = 2 / (period + 1);
  ema[0] = data[0];
  for (let i = 1; i < data.length; i++) {
    ema[i] = data[i] * k + ema[i - 1] * (1 - k);
  }
  return ema;
}

export function calculateSMA(data: number[], period: number): number[] {
  const sma: number[] = [];
  for (let i = 0; i < data.length; i++) {
    if (i < period - 1) {
      sma[i] = NaN;
    } else {
      let sum = 0;
      for (let j = 0; j < period; j++) {
        sum += data[i - j];
      }
      sma[i] = sum / period;
    }
  }
  return sma;
}

export function calculateBollingerBands(data: number[], period: number, stdDev: number) {
  const sma = calculateSMA(data, period);
  const upper: number[] = [];
  const middle: number[] = [];
  const lower: number[] = [];

  for (let i = 0; i < data.length; i++) {
    if (i < period - 1) {
      upper[i] = NaN;
      middle[i] = NaN;
      lower[i] = NaN;
    } else {
      middle[i] = sma[i];
      let sumSquares = 0;
      for (let j = 0; j < period; j++) {
        sumSquares += Math.pow(data[i - j] - sma[i], 2);
      }
      const std = Math.sqrt(sumSquares / period);
      upper[i] = sma[i] + stdDev * std;
      lower[i] = sma[i] - stdDev * std;
    }
  }
  return { upper, middle, lower };
}

export function calculateRSI(data: number[], period: number): number[] {
  const rsi: number[] = [];
  const gains: number[] = [];
  const losses: number[] = [];

  for (let i = 1; i < data.length; i++) {
    const change = data[i] - data[i - 1];
    gains.push(change > 0 ? change : 0);
    losses.push(change < 0 ? -change : 0);
  }

  for (let i = 0; i < data.length; i++) {
    if (i < period) {
      rsi[i] = NaN;
    } else {
      let avgGain = 0;
      let avgLoss = 0;
      for (let j = 0; j < period; j++) {
        avgGain += gains[i - j - 1];
        avgLoss += losses[i - j - 1];
      }
      avgGain /= period;
      avgLoss /= period;
      const rs = avgLoss === 0 ? 100 : avgGain / avgLoss;
      rsi[i] = 100 - (100 / (1 + rs));
    }
  }
  return rsi;
}

export function calculateMACD(data: number[], fastPeriod: number, slowPeriod: number, signalPeriod: number) {
  const fastEMA = calculateEMA(data, fastPeriod);
  const slowEMA = calculateEMA(data, slowPeriod);
  const macdLine: number[] = fastEMA.map((fast, i) => fast - slowEMA[i]);
  const signalLine = calculateEMA(macdLine, signalPeriod);
  const histogram: number[] = macdLine.map((macd, i) => macd - signalLine[i]);
  return { macdLine, signalLine, histogram };
}

export function calculateStochastic(high: number[], low: number[], close: number[], period: number, smoothK: number, smoothD: number) {
  const k: number[] = [];

  for (let i = 0; i < close.length; i++) {
    if (i < period - 1) {
      k[i] = NaN;
    } else {
      let highest = high[i];
      let lowest = low[i];
      for (let j = 0; j < period; j++) {
        if (high[i - j] > highest) highest = high[i - j];
        if (low[i - j] < lowest) lowest = low[i - j];
      }
      k[i] = lowest === highest ? 50 : ((close[i] - lowest) / (highest - lowest)) * 100;
    }
  }

  const smoothedK = calculateSMA(k, smoothK);
  const d = calculateSMA(smoothedK, smoothD);
  return { k: smoothedK, d };
}

export function calculateStochasticRSI(rsi: number[], period: number, smoothK: number, smoothD: number) {
  const stochRSI: number[] = [];

  for (let i = 0; i < rsi.length; i++) {
    if (i < period - 1 || isNaN(rsi[i])) {
      stochRSI[i] = NaN;
    } else {
      let highest = rsi[i];
      let lowest = rsi[i];
      for (let j = 0; j < period; j++) {
        if (!isNaN(rsi[i - j])) {
          if (rsi[i - j] > highest) highest = rsi[i - j];
          if (rsi[i - j] < lowest) lowest = rsi[i - j];
        }
      }
      stochRSI[i] = lowest === highest ? 50 : ((rsi[i] - lowest) / (highest - lowest)) * 100;
    }
  }

  const k = calculateSMA(stochRSI, smoothK);
  const d = calculateSMA(k, smoothD);
  return { k, d };
}

export function calculateADX(high: number[], low: number[], close: number[], period: number): number[] {
  const tr: number[] = [];
  const plusDM: number[] = [];
  const minusDM: number[] = [];

  for (let i = 1; i < close.length; i++) {
    const hl = high[i] - low[i];
    const hc = Math.abs(high[i] - close[i - 1]);
    const lc = Math.abs(low[i] - close[i - 1]);
    tr[i] = Math.max(hl, hc, lc);

    const highDiff = high[i] - high[i - 1];
    const lowDiff = low[i - 1] - low[i];
    plusDM[i] = (highDiff > lowDiff && highDiff > 0) ? highDiff : 0;
    minusDM[i] = (lowDiff > highDiff && lowDiff > 0) ? lowDiff : 0;
  }

  const atr = calculateSMA(tr, period);
  const plusDI: number[] = [];
  const minusDI: number[] = [];
  const dx: number[] = [];

  for (let i = period; i < close.length; i++) {
    let sumPlusDM = 0;
    let sumMinusDM = 0;
    for (let j = 0; j < period; j++) {
      sumPlusDM += plusDM[i - j];
      sumMinusDM += minusDM[i - j];
    }
    plusDI[i] = atr[i] === 0 ? 0 : (sumPlusDM / period / atr[i]) * 100;
    minusDI[i] = atr[i] === 0 ? 0 : (sumMinusDM / period / atr[i]) * 100;
    const diSum = plusDI[i] + minusDI[i];
    dx[i] = diSum === 0 ? 0 : (Math.abs(plusDI[i] - minusDI[i]) / diSum) * 100;
  }

  const adx = calculateSMA(dx, period);
  return adx;
}

// Function to draw all indicators on the chart
export function drawIndicators(
  chart: IChartApi,
  data: CandlestickData[],
  rows: BarRow[],
  indicatorSeriesRef: React.MutableRefObject<Record<string, ISeriesApi<any>>>,
  candlestickSeries?: ISeriesApi<any>
) {
  // Clear previous indicator series
  Object.values(indicatorSeriesRef.current).forEach(s => {
    try {
      chart.removeSeries(s);
    } catch {}
  });
  indicatorSeriesRef.current = {};

  const closes = rows.map(r => r.close);
  const highs = rows.map(r => r.high);
  const lows = rows.map(r => r.low);
  const times = data.map(d => d.time);

  // 1. EMA 10, 50, 200 (changed from 100 to 200)
  const ema10 = calculateEMA(closes, 10);
  const ema50 = calculateEMA(closes, 50);
  const ema200 = calculateEMA(closes, 200);

  const ema10Series = chart.addLineSeries({ color: '#26a69a', lineWidth: 2, title: 'EMA 10' }); // Green
  const ema50Series = chart.addLineSeries({ color: '#FF9800', lineWidth: 2, title: 'EMA 50' }); // Orange
  const ema200Series = chart.addLineSeries({ color: '#E74C3C', lineWidth: 3, title: 'EMA 200' }); // Bold Red

  const ema10Data = times.map((t, i) => ({ time: t, value: ema10[i] })).filter(d => !isNaN(d.value));
  const ema50Data = times.map((t, i) => ({ time: t, value: ema50[i] })).filter(d => !isNaN(d.value));
  const ema200Data = times.map((t, i) => ({ time: t, value: ema200[i] })).filter(d => !isNaN(d.value));

  ema10Series.setData(ema10Data);
  ema50Series.setData(ema50Data);
  ema200Series.setData(ema200Data);

  indicatorSeriesRef.current['ema10'] = ema10Series;
  indicatorSeriesRef.current['ema50'] = ema50Series;
  indicatorSeriesRef.current['ema200'] = ema200Series;

  // Detect EMA crossovers and add markers
  const crossoverMarkers: Array<{
    time: number;
    position: 'belowBar' | 'aboveBar';
    color: string;
    shape: 'arrowUp' | 'arrowDown' | 'circle';
    text: string;
  }> = [];

  // Check for EMA crossovers
  for (let i = 1; i < closes.length; i++) {
    if (isNaN(ema10[i]) || isNaN(ema50[i]) || isNaN(ema200[i])) continue;

    // EMA 10 crosses EMA 50
    if (ema10[i - 1] < ema50[i - 1] && ema10[i] > ema50[i]) {
      // Bullish crossover (10 crosses above 50)
      crossoverMarkers.push({
        time: times[i] as number,
        position: 'belowBar',
        color: '#26a69a',
        shape: 'arrowUp',
        text: '10×50↑',
      });
    } else if (ema10[i - 1] > ema50[i - 1] && ema10[i] < ema50[i]) {
      // Bearish crossover (10 crosses below 50)
      crossoverMarkers.push({
        time: times[i] as number,
        position: 'aboveBar',
        color: '#ef5350',
        shape: 'arrowDown',
        text: '10×50↓',
      });
    }

    // EMA 10 crosses EMA 200
    if (ema10[i - 1] < ema200[i - 1] && ema10[i] > ema200[i]) {
      crossoverMarkers.push({
        time: times[i] as number,
        position: 'belowBar',
        color: '#1976d2',
        shape: 'arrowUp',
        text: '10×200↑',
      });
    } else if (ema10[i - 1] > ema200[i - 1] && ema10[i] < ema200[i]) {
      crossoverMarkers.push({
        time: times[i] as number,
        position: 'aboveBar',
        color: '#d32f2f',
        shape: 'arrowDown',
        text: '10×200↓',
      });
    }

    // EMA 50 crosses EMA 200 (Golden/Death Cross)
    if (ema50[i - 1] < ema200[i - 1] && ema50[i] > ema200[i]) {
      // Golden Cross
      crossoverMarkers.push({
        time: times[i] as number,
        position: 'belowBar',
        color: '#FFD700',
        shape: 'circle',
        text: 'Golden Cross',
      });
    } else if (ema50[i - 1] > ema200[i - 1] && ema50[i] < ema200[i]) {
      // Death Cross
      crossoverMarkers.push({
        time: times[i] as number,
        position: 'aboveBar',
        color: '#8B0000',
        shape: 'circle',
        text: 'Death Cross',
      });
    }
  }

  // Add crossover markers to the candlestick series
  if (crossoverMarkers.length > 0 && candlestickSeries) {
    candlestickSeries.setMarkers(crossoverMarkers as any);
  }

  // 2. Bollinger Bands (20, 2)
  const bb = calculateBollingerBands(closes, 20, 2);
  const bbUpperSeries = chart.addLineSeries({ color: '#95A5A6', lineWidth: 1, title: 'BB Upper' });
  const bbMiddleSeries = chart.addLineSeries({ color: '#7F8C8D', lineWidth: 1, title: 'BB Middle' });
  const bbLowerSeries = chart.addLineSeries({ color: '#95A5A6', lineWidth: 1, title: 'BB Lower' });

  bbUpperSeries.setData(times.map((t, i) => ({ time: t, value: bb.upper[i] })).filter(d => !isNaN(d.value)));
  bbMiddleSeries.setData(times.map((t, i) => ({ time: t, value: bb.middle[i] })).filter(d => !isNaN(d.value)));
  bbLowerSeries.setData(times.map((t, i) => ({ time: t, value: bb.lower[i] })).filter(d => !isNaN(d.value)));

  indicatorSeriesRef.current['bbUpper'] = bbUpperSeries;
  indicatorSeriesRef.current['bbMiddle'] = bbMiddleSeries;
  indicatorSeriesRef.current['bbLower'] = bbLowerSeries;
}
