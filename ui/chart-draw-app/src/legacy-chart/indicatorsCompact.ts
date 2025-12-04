import { type IChartApi, type CandlestickData, type ISeriesApi } from "lightweight-charts";
import {
  calculateEMA,
  calculateBollingerBands,
  calculateRSI,
  calculateMACD,
  calculateStochastic,
  calculateStochasticRSI,
  calculateADX,
} from "./indicators";

type BarRow = {
  time?: number;
  timestamp?: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

/**
 * Draw all indicators on a single chart using separate price scales
 * This is designed for multi-panel grids where we can't have separate oscillator panes
 */
export function drawIndicatorsCompact(
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

  // 1. EMA 10, 50, 200 on main price scale
  const ema10 = calculateEMA(closes, 10);
  const ema50 = calculateEMA(closes, 50);
  const ema200 = calculateEMA(closes, 200);

  const ema10Series = chart.addLineSeries({ color: '#26a69a', lineWidth: 1, title: 'EMA 10', priceScaleId: 'right' });
  const ema50Series = chart.addLineSeries({ color: '#FF9800', lineWidth: 1, title: 'EMA 50', priceScaleId: 'right' });
  const ema200Series = chart.addLineSeries({ color: '#E74C3C', lineWidth: 2, title: 'EMA 200', priceScaleId: 'right' });

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

  for (let i = 1; i < closes.length; i++) {
    if (isNaN(ema10[i]) || isNaN(ema50[i]) || isNaN(ema200[i])) continue;

    // EMA 10 crosses EMA 50
    if (ema10[i - 1] < ema50[i - 1] && ema10[i] > ema50[i]) {
      crossoverMarkers.push({
        time: times[i] as number,
        position: 'belowBar',
        color: '#26a69a',
        shape: 'circle',
        text: '',
      });
    } else if (ema10[i - 1] > ema50[i - 1] && ema10[i] < ema50[i]) {
      crossoverMarkers.push({
        time: times[i] as number,
        position: 'aboveBar',
        color: '#ef5350',
        shape: 'circle',
        text: '',
      });
    }

    // EMA 50 crosses EMA 200 (Golden/Death Cross)
    if (ema50[i - 1] < ema200[i - 1] && ema50[i] > ema200[i]) {
      crossoverMarkers.push({
        time: times[i] as number,
        position: 'belowBar',
        color: '#FFD700',
        shape: 'circle',
        text: '',
      });
    } else if (ema50[i - 1] > ema200[i - 1] && ema50[i] < ema200[i]) {
      crossoverMarkers.push({
        time: times[i] as number,
        position: 'aboveBar',
        color: '#8B0000',
        shape: 'circle',
        text: '',
      });
    }
  }

  if (crossoverMarkers.length > 0 && candlestickSeries) {
    candlestickSeries.setMarkers(crossoverMarkers as any);
  }

  // 2. Bollinger Bands on main price scale
  const bb = calculateBollingerBands(closes, 20, 2);
  const bbUpperSeries = chart.addLineSeries({ color: '#95A5A6', lineWidth: 1, title: 'BB Upper', priceScaleId: 'right' });
  const bbMiddleSeries = chart.addLineSeries({ color: '#7F8C8D', lineWidth: 1, title: 'BB Middle', priceScaleId: 'right' });
  const bbLowerSeries = chart.addLineSeries({ color: '#95A5A6', lineWidth: 1, title: 'BB Lower', priceScaleId: 'right' });

  bbUpperSeries.setData(times.map((t, i) => ({ time: t, value: bb.upper[i] })).filter(d => !isNaN(d.value)));
  bbMiddleSeries.setData(times.map((t, i) => ({ time: t, value: bb.middle[i] })).filter(d => !isNaN(d.value)));
  bbLowerSeries.setData(times.map((t, i) => ({ time: t, value: bb.lower[i] })).filter(d => !isNaN(d.value)));

  indicatorSeriesRef.current['bbUpper'] = bbUpperSeries;
  indicatorSeriesRef.current['bbMiddle'] = bbMiddleSeries;
  indicatorSeriesRef.current['bbLower'] = bbLowerSeries;

  // 3. RSI on separate scale (overlaid)
  const rsi = calculateRSI(closes, 14);
  const rsiSeries = chart.addLineSeries({
    color: '#9B59B6',
    lineWidth: 1,
    title: 'RSI',
    priceScaleId: 'rsi',
  });
  chart.priceScale('rsi').applyOptions({
    scaleMargins: { top: 0.85, bottom: 0.0 },
  });
  rsiSeries.setData(times.map((t, i) => ({ time: t, value: rsi[i] })).filter(d => !isNaN(d.value)));
  indicatorSeriesRef.current['rsi'] = rsiSeries;

  // 4. MACD on separate scale (overlaid)
  const macd = calculateMACD(closes, 12, 26, 9);
  const macdLineSeries = chart.addLineSeries({
    color: '#3498DB',
    lineWidth: 1,
    title: 'MACD',
    priceScaleId: 'macd',
  });
  const macdSignalSeries = chart.addLineSeries({
    color: '#E74C3C',
    lineWidth: 1,
    title: 'Signal',
    priceScaleId: 'macd',
  });
  chart.priceScale('macd').applyOptions({
    scaleMargins: { top: 0.70, bottom: 0.15 },
  });
  macdLineSeries.setData(times.map((t, i) => ({ time: t, value: macd.macdLine[i] })).filter(d => !isNaN(d.value)));
  macdSignalSeries.setData(times.map((t, i) => ({ time: t, value: macd.signalLine[i] })).filter(d => !isNaN(d.value)));
  indicatorSeriesRef.current['macdLine'] = macdLineSeries;
  indicatorSeriesRef.current['macdSignal'] = macdSignalSeries;
}
