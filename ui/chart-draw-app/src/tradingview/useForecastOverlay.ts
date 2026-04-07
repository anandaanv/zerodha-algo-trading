import { useRef, useEffect } from 'react';
import type { ForecastResponse, ModelForecast } from './forecastApi';

export const MODEL_COLORS = {
  chronos: '#FF8C42',   // orange
  lagLlama: '#00B0FF',  // cyan
  granite: '#00E676',   // green
} as const;

type ModelKey = keyof typeof MODEL_COLORS;

interface ModelToggles {
  chronos: boolean;
  lagLlama: boolean;
  granite: boolean;
}

function drawModelForecast(
  chart: any,
  anchorTs: number,
  anchorPrice: number,
  futureTimestamps: number[],
  model: ModelForecast,
  color: string,
): any[] {
  if (!model.available || model.forecastMedian.length === 0) return [];

  const ids: any[] = [];

  const toPoints = (values: number[]) => [
    { time: anchorTs, price: anchorPrice },
    ...futureTimestamps.slice(0, values.length).map((ts, i) => ({
      time: ts,
      price: values[i],
    })),
  ];

  // Median path — solid, full colour
  try {
    const id = chart.createMultipointShape(
      toPoints(model.forecastMedian),
      {
        shape: 'path',
        lock: true,
        disableSelection: true,
        disableSave: true,
        overrides: { linecolor: color, linewidth: 2, linestyle: 0 },
      },
    );
    if (id) ids.push(id);
  } catch { /* fall back silently if TV doesn't support path */ }

  // Upper band (90th pct) — dashed, semi-transparent
  if (model.forecastHigh.length > 0) {
    try {
      const id = chart.createMultipointShape(
        toPoints(model.forecastHigh),
        {
          shape: 'path',
          lock: true,
          disableSelection: true,
          disableSave: true,
          overrides: { linecolor: color, linewidth: 1, linestyle: 2, transparency: 60 },
        },
      );
      if (id) ids.push(id);
    } catch { /* ignore */ }
  }

  // Lower band (10th pct) — dashed, semi-transparent
  if (model.forecastLow.length > 0) {
    try {
      const id = chart.createMultipointShape(
        toPoints(model.forecastLow),
        {
          shape: 'path',
          lock: true,
          disableSelection: true,
          disableSave: true,
          overrides: { linecolor: color, linewidth: 1, linestyle: 2, transparency: 60 },
        },
      );
      if (id) ids.push(id);
    } catch { /* ignore */ }
  }

  return ids;
}

export function clearForecastShapes(chart: any, ids: any[]): void {
  ids.forEach(id => {
    try { chart.removeEntity(id); } catch { /* ignore */ }
  });
}

export function drawForecastOverlay(
  chart: any,
  data: ForecastResponse,
  toggles: ModelToggles,
): any[] {
  const all: any[] = [];

  const models: Array<{ key: ModelKey; forecast: ModelForecast }> = [
    { key: 'chronos', forecast: data.chronos },
    { key: 'lagLlama', forecast: data.lagLlama },
    { key: 'granite', forecast: data.granite },
  ];

  for (const { key, forecast } of models) {
    if (!toggles[key]) continue;
    const ids = drawModelForecast(
      chart,
      data.anchorTimestamp,
      data.anchorPrice,
      data.futureTimestamps,
      forecast,
      MODEL_COLORS[key],
    );
    all.push(...ids);
  }

  return all;
}
