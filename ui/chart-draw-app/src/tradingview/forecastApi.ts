import { addServiceTokenToUrl, withAuth } from '../utils/apiHelper';
import { apiFetch, getApiUrl } from '../config/api';

export interface ModelForecast {
  model: string;
  available: boolean;
  error?: string;
  forecastLow: number[];
  forecastMedian: number[];
  forecastHigh: number[];
  convergenceMetric: number;
}

export interface ForecastResponse {
  symbol: string;
  interval: string;
  horizon: number;
  anchorTimestamp: number;
  anchorPrice: number;
  futureTimestamps: number[];
  chronos: ModelForecast;
  lagLlama: ModelForecast;
  granite: ModelForecast;
}

export async function fetchForecast(
  symbol: string,
  interval: string,
  horizon: number
): Promise<ForecastResponse> {
  const url = getApiUrl('/api/forecast');
  url.searchParams.set('symbol', symbol);
  url.searchParams.set('interval', interval);
  url.searchParams.set('horizon', String(horizon));
  addServiceTokenToUrl(url);
  const res = await apiFetch(url.toString(), withAuth());
  if (!res.ok) throw new Error(`forecast fetch failed: ${res.status}`);
  return res.json();
}
