import { apiFetch } from "../config/api";
import { withAuth } from "../utils/apiHelper";

export interface PatternDto {
  patternType: string;
  bullish: boolean;
  keyLevel: number;
  keyLevelTime: string;
  target: number;
  patternHeight: number;
  atr: number;
  rsiAtP1: number;
  rsiAtP2: number;
  p0Time: string;
  p1Time: string;
}

export interface PatternScanResultDto {
  symbol: string;
  watchingTf: string;
  confirmTf: string;
  patterns: PatternDto[];
  watchingTfOverlays: Record<string, any[]>;
  confirmTfOverlays: Record<string, any[]>;
}

export async function getNifty50(): Promise<string[]> {
  const res = await apiFetch('/api/pattern-scan/nifty50', withAuth());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function scanSymbol(
  symbol: string,
  watchingTf: string,
  confirmTf: string
): Promise<PatternScanResultDto> {
  const params = new URLSearchParams({
    watchingTf,
    confirmTf,
  });
  const res = await apiFetch(
    `/api/pattern-scan/${encodeURIComponent(symbol)}?${params.toString()}`,
    withAuth()
  );
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Request failed: ${res.status}`);
  }
  return res.json();
}
