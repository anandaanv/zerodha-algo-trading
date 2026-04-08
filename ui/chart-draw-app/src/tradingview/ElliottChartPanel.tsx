import React, { useState, useCallback } from 'react';
import { runFullElliott, triggerAnalysis } from './elliottApi';

interface Props {
  open: boolean;
  onClose: () => void;
  symbol: string;
  timeframes: string[];
  layoutId: number | null;
  getChartState?: () => string;
}

type LoadingAction = 'identify' | 'ai-call' | 'full' | null;

export default function ElliottChartPanel({ open, onClose, symbol, timeframes, layoutId, getChartState }: Props) {
  const [loading, setLoading] = useState<LoadingAction>(null);
  const [result, setResult] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);

  const primaryTf = timeframes[0] ?? '1D';
  const tfParam = timeframes.join(',');

  const handleIdentify = useCallback(async () => {
    setLoading('identify'); setError(null); setResult(null);
    try {
      setResult(await runFullElliott(symbol, primaryTf, tfParam, false));
    } catch (e: any) { setError(e.message ?? 'Identification failed'); }
    finally { setLoading(null); }
  }, [symbol, primaryTf, tfParam]);

  const handleAiCall = useCallback(async () => {
    setLoading('ai-call'); setError(null); setResult(null);
    try {
      setResult(await runFullElliott(symbol, primaryTf, tfParam, true));
    } catch (e: any) { setError(e.message ?? 'AI call failed'); }
    finally { setLoading(null); }
  }, [symbol, primaryTf, tfParam]);

  const handleFull = useCallback(async () => {
    if (!layoutId) { setError('No layout ID. Save the layout first.'); return; }
    setLoading('full'); setError(null); setResult(null);
    try {
      let drawingsJson: string | undefined;
      try { drawingsJson = getChartState?.(); } catch { /* ignore */ }
      setResult(await triggerAnalysis(layoutId, symbol, drawingsJson, timeframes, true));
    } catch (e: any) { setError(e.message ?? 'Full analysis failed'); }
    finally { setLoading(null); }
  }, [layoutId, symbol, timeframes, getChartState]);

  return (
    <div style={{ width: 400, height: '100%', background: '#fff', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      {/* Header */}
      <div style={{ background: '#1a237e', color: '#fff', padding: '10px 14px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0 }}>
        <div>
          <div style={{ fontWeight: 700, fontSize: 14 }}>Elliott Analysis</div>
          <div style={{ fontSize: 11, opacity: 0.7 }}>{symbol} · {timeframes.join(', ')}</div>
        </div>
        <button onClick={onClose} style={miniBtn}>✕</button>
      </div>

      {/* Action buttons */}
      <div style={{ padding: '8px 14px', background: '#f8f9ff', borderBottom: '1px solid #e8eaf6', display: 'flex', gap: 6, flexShrink: 0 }}>
        <button onClick={handleIdentify} disabled={loading !== null} style={actionBtn(loading === 'identify', '#4caf50')}>
          {loading === 'identify' ? 'Identifying…' : 'Elliott - Identification'}
        </button>
        <button onClick={handleAiCall} disabled={loading !== null} style={actionBtn(loading === 'ai-call', '#ff9800')}>
          {loading === 'ai-call' ? 'Calling AI…' : 'Elliott - AI Call'}
        </button>
        <button onClick={handleFull} disabled={loading !== null} style={actionBtn(loading === 'full', '#3f51b5')}>
          {loading === 'full' ? 'Running…' : 'Full'}
        </button>
      </div>

      {error && (
        <div style={{ margin: '8px 14px', padding: '6px 10px', background: '#ffebee', border: '1px solid #ef9a9a', borderRadius: 6, color: '#c62828', fontSize: 11, flexShrink: 0 }}>
          {error}
        </div>
      )}

      <div style={{ flex: 1, overflowY: 'auto', padding: '10px 14px' }}>
        {!result && !loading && (
          <div style={{ textAlign: 'center', color: '#bbb', padding: '40px 10px', fontSize: 12 }}>
            Click a button above to run analysis.
          </div>
        )}

        {loading && (
          <div style={{ textAlign: 'center', color: '#90caf9', padding: '40px 10px', fontSize: 12 }}>
            Running {loading === 'identify' ? 'Elliott identification' : loading === 'ai-call' ? 'Elliott AI call' : 'full analysis'}…
          </div>
        )}

        {result && (
          <div style={{ fontSize: 12 }}>
            {/* AI Finding */}
            {result.hypothesisLabel && (
              <div style={{ marginBottom: 12 }}>
                <div style={{ fontWeight: 700, fontSize: 13, color: '#1a237e', marginBottom: 4 }}>{result.hypothesisLabel}</div>
                {result.currentStage && (
                  <span style={{ fontSize: 10, background: stageColor(result.currentStage), color: '#fff', borderRadius: 3, padding: '2px 6px', marginBottom: 6, display: 'inline-block' }}>
                    {result.currentStage}
                  </span>
                )}
                {result.hypothesisDescription && <div style={{ color: '#444', marginTop: 6 }}>{result.hypothesisDescription}</div>}
                {result.waveContext && <div style={{ color: '#666', marginTop: 4, fontStyle: 'italic' }}>{result.waveContext}</div>}
              </div>
            )}

            {/* Entry */}
            {result.anticipatoryEntry && (
              <div style={{ marginBottom: 10, padding: '8px 10px', background: '#f3f8ff', border: '1px solid #90caf9', borderRadius: 6 }}>
                <div style={{ fontWeight: 600, color: '#1565c0', marginBottom: 4 }}>
                  Anticipatory Entry · {result.anticipatoryEntry.direction}
                </div>
                {result.anticipatoryEntry.entryZone && <div>Entry: {result.anticipatoryEntry.entryZone}</div>}
                {result.anticipatoryEntry.stopLoss && <div>SL: {result.anticipatoryEntry.stopLoss}</div>}
                {result.anticipatoryEntry.target1 && <div>T1: {result.anticipatoryEntry.target1}</div>}
                {result.anticipatoryEntry.target2 && <div>T2: {result.anticipatoryEntry.target2}</div>}
                {result.anticipatoryEntry.rationale && <div style={{ color: '#666', marginTop: 4, fontStyle: 'italic' }}>{result.anticipatoryEntry.rationale}</div>}
              </div>
            )}

            {/* Invalidation */}
            {result.invalidationConditions?.length > 0 && (
              <div style={{ marginBottom: 10 }}>
                <div style={{ fontWeight: 600, color: '#c62828', marginBottom: 3 }}>Invalidation</div>
                {result.invalidationConditions.map((c: string, i: number) => (
                  <div key={i} style={{ color: '#c62828' }}>• {c}</div>
                ))}
              </div>
            )}

            {/* Anomaly flags */}
            {result.anomalyFlags?.length > 0 && (
              <div style={{ marginBottom: 10 }}>
                <div style={{ fontWeight: 600, color: '#e65100', marginBottom: 3 }}>Flags</div>
                {result.anomalyFlags.map((f: string, i: number) => (
                  <div key={i} style={{ color: '#e65100' }}>⚠ {f}</div>
                ))}
              </div>
            )}

            {/* Wave counts (no-AI path) */}
            {result.waveCounts?.length > 0 && (
              <div style={{ marginBottom: 10 }}>
                <div style={{ fontWeight: 600, color: '#555', marginBottom: 3 }}>Wave Counts</div>
                {result.waveCounts.slice(0, 5).map((wc: any, i: number) => (
                  <div key={i} style={{ padding: '4px 8px', background: '#fafafa', border: '1px solid #eee', borderRadius: 4, marginBottom: 4 }}>
                    <span style={{ fontWeight: 600 }}>[{wc.primaryTimeframe}]</span>{' '}
                    {wc.waveType} · {wc.bullish ? 'BULL' : 'BEAR'} · score={wc.totalScore ?? wc.fibonacciScore}
                    {wc.currentPositionDescription && (
                      <div style={{ color: '#777', fontSize: 11 }}>{wc.currentPositionDescription}</div>
                    )}
                  </div>
                ))}
              </div>
            )}

            {/* Scenarios */}
            {result.scenarios?.length > 0 && (
              <div style={{ marginBottom: 10 }}>
                <div style={{ fontWeight: 600, color: '#555', marginBottom: 3 }}>Scenarios</div>
                {result.scenarios.slice(0, 3).map((sc: any, i: number) => (
                  <div key={i} style={{ padding: '4px 8px', background: '#fafafa', border: '1px solid #eee', borderRadius: 4, marginBottom: 4 }}>
                    <span style={{ fontWeight: 600 }}>{sc.id}</span> · {sc.directionLabel ?? sc.direction}
                    {sc.scenarioInvalidation && (
                      <span style={{ color: '#c62828', marginLeft: 6 }}>floor={sc.scenarioInvalidation}</span>
                    )}
                  </div>
                ))}
              </div>
            )}

            {/* Reasoning */}
            {result.reasoning && (
              <div style={{ marginTop: 10, padding: '8px 10px', background: '#f9fbe7', border: '1px solid #dce775', borderRadius: 6, color: '#555', fontStyle: 'italic' }}>
                {result.reasoning}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function stageColor(stage: string): string {
  if (stage === 'ENTRY_READY') return '#388e3c';
  if (stage === 'INVALIDATED') return '#c62828';
  return '#607d8b';
}

function actionBtn(isLoading: boolean, color: string): React.CSSProperties {
  return {
    flex: 1, padding: '6px 6px',
    background: isLoading ? '#bbb' : color,
    color: '#fff', border: 'none', borderRadius: 5,
    cursor: isLoading ? 'default' : 'pointer',
    fontWeight: 700, fontSize: 10,
    whiteSpace: 'nowrap',
  };
}

const miniBtn: React.CSSProperties = {
  padding: '3px 8px', background: 'rgba(255,255,255,0.15)', color: '#fff',
  border: '1px solid rgba(255,255,255,0.3)', borderRadius: 5, cursor: 'pointer', fontSize: 11,
};
