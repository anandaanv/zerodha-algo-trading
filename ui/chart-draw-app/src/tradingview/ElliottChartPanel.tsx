import React, { useState, useCallback } from 'react';
import { runFullElliott } from './elliottApi';

interface Props {
  open: boolean;
  onClose: () => void;
  symbol: string;
  timeframes: string[];
  layoutId: number | null;
  getChartState?: () => string;
  onPatternsDetected?: (patterns: any[]) => void;
}

type LoadingAction = 'scan' | 'scan-ai' | null;

export default function ElliottChartPanel({ open, onClose, symbol, timeframes, layoutId, getChartState, onPatternsDetected }: Props) {
  const [loading, setLoading] = useState<LoadingAction>(null);
  const [result, setResult] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);

  const primaryTf = timeframes[0] ?? '1D';
  const tfParam = timeframes.join(',');

  const runScan = useCallback(async (withAi: boolean) => {
    const action: LoadingAction = withAi ? 'scan-ai' : 'scan';
    setLoading(action); setError(null); setResult(null);
    try {
      const res = await runFullElliott(symbol, primaryTf, tfParam, withAi);
      setResult(res);
      const patterns = res?.waveAnalysis?.allPatterns ?? [];
      if (patterns.length > 0) onPatternsDetected?.(patterns);
    } catch (e: any) { setError(e.message ?? 'Scan failed'); }
    finally { setLoading(null); }
  }, [symbol, primaryTf, tfParam, onPatternsDetected]);

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
      <div style={{ padding: '8px 14px', background: '#f8f9ff', borderBottom: '1px solid #e8eaf6', display: 'flex', gap: 8, flexShrink: 0 }}>
        <button onClick={() => runScan(false)} disabled={loading !== null} style={actionBtn(loading === 'scan', '#4caf50')}>
          {loading === 'scan' ? 'Scanning…' : 'Scan'}
        </button>
        <button onClick={() => runScan(true)} disabled={loading !== null} style={actionBtn(loading === 'scan-ai', '#3f51b5')}>
          {loading === 'scan-ai' ? 'Running AI…' : 'Scan + AI'}
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
            {loading === 'scan-ai' ? 'Running scan + AI analysis…' : 'Running Elliott scan…'}
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

            {/* Detected Patterns (from waveAnalysis) */}
            {result.waveAnalysis?.allPatterns?.length > 0 && (
              <div style={{ marginBottom: 10 }}>
                <div style={{ fontWeight: 600, color: '#555', marginBottom: 3 }}>Detected Patterns</div>
                {result.waveAnalysis.allPatterns
                  .filter((p: any) => p.status !== 'INVALIDATED')
                  .slice(0, 8)
                  .map((p: any, i: number) => {
                    const isBull = ['DOUBLE_BOTTOM','TRIPLE_BOTTOM','INVERTED_HEAD_AND_SHOULDERS','CUP_AND_HANDLE','ROUNDING_BOTTOM','ASCENDING_TRIANGLE','BULL_FLAG','BULL_PENNANT','FALLING_WEDGE'].includes(p.type);
                    const statusColor = p.status === 'CONFIRMED' ? '#388e3c' : p.status === 'WATCHING' ? '#f57c00' : '#607d8b';
                    return (
                      <div key={i} style={{ padding: '4px 8px', background: '#fafafa', border: `1px solid ${statusColor}`, borderRadius: 4, marginBottom: 4 }}>
                        <span style={{ fontWeight: 600, color: isBull ? '#2e7d32' : '#c62828' }}>{p.type?.replace(/_/g, ' ')}</span>
                        {p.timeframe && <span style={{ color: '#888', marginLeft: 6, fontSize: 10 }}>[{p.timeframe}]</span>}
                        <span style={{ color: statusColor, marginLeft: 6, fontSize: 10 }}>{p.status}</span>
                        {p.neckline && <div style={{ color: '#555', fontSize: 10 }}>neck={p.neckline?.toFixed(1)}</div>}
                        {p.target && <div style={{ color: isBull ? '#2e7d32' : '#c62828', fontSize: 10 }}>target={p.target?.toFixed(1)}</div>}
                      </div>
                    );
                  })}
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
