import React, { useState, useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import type { CopilotHypothesis, CopilotAnomalyFlag, CopilotActiveTrade, CopilotObservation } from './copilotTypes';
import {
  scanAnalysis, reasonAnalysis, triggerAnalysis,
  getHypothesisBoard, getTradeDashboard,
  confirmHypothesis, dismissHypothesis, acknowledgeFlag,
} from './copilotApi';
import ElliottPanel from './ElliottPanel';
import { runFullElliott } from './copilotApi';
import type { AdvancedElliottResult } from './copilotTypes';

export interface CopilotPanelState {
  investigationId: number | null;
  observations: CopilotObservation[];
  hypotheses: CopilotHypothesis[];
  flags: CopilotAnomalyFlag[];
  trades: CopilotActiveTrade[];
}

interface Props {
  open: boolean;
  onClose: () => void;
  symbol: string;
  timeframes: string[];
  layoutId: number | null;
  getChartState?: () => string;
  onHypothesesLoaded?: (hypotheses: CopilotHypothesis[], investigationId: number) => void;
  onObservationsLoaded?: (observations: CopilotObservation[], investigationId: number) => void;
}

const STATE_COLOR: Record<string, string> = {
  WATCHING:    '#607d8b',
  BUILDING:    '#f57c00',
  CONFIRMED:   '#388e3c',
  TRADE_ACTIVE:'#1976d2',
  INVALIDATED: '#c62828',
  EXPIRED:     '#9e9e9e',
};

const CONFIDENCE_COLOR: Record<string, string> = {
  HIGH:   '#388e3c',
  MEDIUM: '#f57c00',
  LOW:    '#c62828',
};

export default function CopilotChartPanel({
  open, onClose, symbol, timeframes, layoutId, getChartState,
  onHypothesesLoaded, onObservationsLoaded,
}: Props) {
  const navigate = useNavigate();
  const [state, setState] = useState<CopilotPanelState>({
    investigationId: null,
    observations: [],
    hypotheses: [],
    flags: [],
    trades: [],
  });
  const [loading, setLoading] = useState(false);
  const [loadingAction, setLoadingAction] = useState<'scan' | 'reason' | 'full' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [expandedObsId, setExpandedObsId] = useState<number | null>(null);
  const [scenarioText, setScenarioText] = useState('');
  const [elliottResult, setElliottResult] = useState<AdvancedElliottResult | null>(null);
  const [elliottLoading, setElliottLoading] = useState(false);
  const [elliottError, setElliottError] = useState<string | null>(null);

  const refresh = useCallback(async (investigationId: number) => {
    const [board, dashboard] = await Promise.all([
      getHypothesisBoard(investigationId),
      getTradeDashboard(investigationId),
    ]);
    setState(prev => ({
      ...prev,
      investigationId,
      hypotheses: board.hypotheses,
      flags: board.unacknowledgedFlags,
      trades: dashboard.activeTrades,
    }));
    onHypothesesLoaded?.(board.hypotheses, investigationId);
  }, [onHypothesesLoaded]);

  // Phase 1: Scan
  const handleScan = useCallback(async (force = false) => {
    if (!layoutId) { setError('No layout ID. Save the layout first.'); return; }
    setLoading(true); setLoadingAction('scan'); setError(null); setWarning(null);
    try {
      let drawingsJson: string | undefined;
      try { drawingsJson = getChartState?.(); } catch { /* ignore */ }
      const res = await scanAnalysis(layoutId, symbol, drawingsJson, timeframes, force);
      if (res.warning) setWarning(res.warning);
      setState(prev => ({
        ...prev,
        investigationId: res.investigationId,
        observations: res.observations,
      }));
      onObservationsLoaded?.(res.observations, res.investigationId);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false); setLoadingAction(null);
    }
  }, [layoutId, symbol, timeframes, getChartState, onObservationsLoaded]);

  // Phase 2: Reason
  const handleReason = useCallback(async () => {
    if (!state.investigationId) { setError('Run scan first.'); return; }
    setLoading(true); setLoadingAction('reason'); setError(null); setWarning(null);
    try {
      let drawingsJson: string | undefined;
      try { drawingsJson = getChartState?.(); } catch { /* ignore */ }
      const res = await reasonAnalysis({
        investigationId: state.investigationId,
        drawingsJson,
        scenarioText: scenarioText.trim() || undefined,
      });
      if (res.warning) setWarning(res.warning);
      setState(prev => ({
        ...prev,
        hypotheses: res.hypotheses,
        flags: res.flags,
        observations: res.observations,
      }));
      onHypothesesLoaded?.(res.hypotheses, state.investigationId);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false); setLoadingAction(null);
    }
  }, [state.investigationId, getChartState, scenarioText, onHypothesesLoaded]);

  // Full: Scan + Reason
  const handleFull = useCallback(async (force = false) => {
    if (!layoutId) { setError('No layout ID. Save the layout first.'); return; }
    setLoading(true); setLoadingAction('full'); setError(null); setWarning(null);
    try {
      let drawingsJson: string | undefined;
      try { drawingsJson = getChartState?.(); } catch { /* ignore */ }
      const res = await triggerAnalysis(layoutId, symbol, drawingsJson, timeframes, force);
      if (res.warning) setWarning(res.warning);
      setState(prev => ({
        ...prev,
        investigationId: res.investigationId,
        observations: res.observations ?? [],
        hypotheses: res.hypotheses,
        flags: res.flags,
      }));
      if (res.observations) onObservationsLoaded?.(res.observations, res.investigationId);
      onHypothesesLoaded?.(res.hypotheses, res.investigationId);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false); setLoadingAction(null);
    }
  }, [layoutId, symbol, timeframes, getChartState, onHypothesesLoaded, onObservationsLoaded]);

  // Auto-trigger on panel open
  useEffect(() => {
    if (open && layoutId && !loading) {
      handleFull(false);
    }
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleFullElliott = useCallback(async () => {
    setElliottLoading(true);
    setElliottError(null);
    try {
      const primaryTf = timeframes[0] ?? '1D';
      const tfParam = timeframes.join(',');
      const result = await runFullElliott(symbol, primaryTf, tfParam);
      setElliottResult(result);
    } catch (e: any) {
      setElliottError(e.message ?? 'Elliott analysis failed');
    } finally {
      setElliottLoading(false);
    }
  }, [symbol, timeframes]);

  const handleConfirm = async (id: number, entryType: string) => {
    try {
      await confirmHypothesis(id, entryType, false);
      if (state.investigationId) await refresh(state.investigationId);
    } catch (e) { setError(String(e)); }
  };

  const handleDismiss = async (id: number) => {
    try {
      await dismissHypothesis(id);
      if (state.investigationId) await refresh(state.investigationId);
    } catch (e) { setError(String(e)); }
  };

  const handleAckFlag = async (flagId: number, action: string) => {
    try {
      await acknowledgeFlag(flagId, action);
      if (state.investigationId) await refresh(state.investigationId);
    } catch (e) { setError(String(e)); }
  };

  const positiveObs = state.observations.filter(o => o.patternDetected);
  const negativeObs = state.observations.filter(o => !o.patternDetected);
  const activeHypotheses = state.hypotheses.filter(h =>
    h.state === 'WATCHING' || h.state === 'BUILDING' || h.state === 'CONFIRMED'
  );
  const tradeActive = state.hypotheses.filter(h => h.state === 'TRADE_ACTIVE');
  const inactive = state.hypotheses.filter(h => h.state === 'INVALIDATED' || h.state === 'EXPIRED');

  return (
    <div style={{
      width: 400, height: '100%', background: '#fff',
      display: 'flex', flexDirection: 'column', overflow: 'hidden',
    }}>
      {/* Header */}
      <div style={{
        background: '#1a237e', color: '#fff', padding: '10px 14px',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0,
      }}>
        <div>
          <div style={{ fontWeight: 700, fontSize: 14 }}>Co-Pilot</div>
          <div style={{ fontSize: 11, opacity: 0.7 }}>{symbol} · {timeframes.join(', ')}</div>
        </div>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          {state.investigationId && (
            <button onClick={() => navigate('/copilot')} style={miniBtn} title="Open full dashboard">Full</button>
          )}
          <button onClick={onClose} style={{ ...miniBtn, fontSize: 16 }}>x</button>
        </div>
      </div>

      {/* Action bar: Scan / Reason / Full */}
      <div style={{
        padding: '8px 14px', background: '#f8f9ff', borderBottom: '1px solid #e8eaf6',
        display: 'flex', gap: 6, alignItems: 'center', flexShrink: 0,
      }}>
        <button
          onClick={() => handleScan(true)}
          disabled={loading}
          style={actionBtnStyle(loading && loadingAction === 'scan', '#4caf50')}
        >
          {loadingAction === 'scan' ? 'Scanning...' : 'Scan'}
        </button>
        <button
          onClick={handleReason}
          disabled={loading || !state.investigationId}
          style={actionBtnStyle(loading && loadingAction === 'reason', '#ff9800')}
        >
          {loadingAction === 'reason' ? 'Reasoning...' : 'Reason'}
        </button>
        <button
          onClick={() => handleFull(true)}
          disabled={loading}
          style={actionBtnStyle(loading && loadingAction === 'full', '#3f51b5')}
        >
          {loadingAction === 'full' ? 'Running...' : 'Full'}
        </button>
        <button
          onClick={handleFullElliott}
          disabled={elliottLoading}
          style={{
            padding: '4px 10px', fontSize: 12, cursor: 'pointer',
            background: elliottLoading ? '#37474f' : '#1a237e',
            color: '#fff', border: 'none', borderRadius: 3,
          }}
        >
          {elliottLoading ? '…' : 'Elliott'}
        </button>
        {state.investigationId && (
          <button
            onClick={() => state.investigationId && refresh(state.investigationId)}
            style={{ ...miniBtn, padding: '6px 8px', background: '#fff', color: '#555', border: '1px solid #ccc', borderRadius: 5, fontSize: 11 }}
            title="Refresh"
          >Refresh</button>
        )}
      </div>

      {/* Investigation badge */}
      {state.investigationId && (
        <div style={{ padding: '4px 14px', background: '#e8eaf6', fontSize: 10, color: '#3f51b5', fontWeight: 600, flexShrink: 0 }}>
          Investigation #{state.investigationId}
        </div>
      )}

      {error && (
        <div style={{ margin: '8px 14px', padding: '6px 10px', background: '#ffebee', border: '1px solid #ef9a9a', borderRadius: 6, color: '#c62828', fontSize: 11, flexShrink: 0 }}>
          {error}
        </div>
      )}
      {warning && (
        <div style={{ margin: '8px 14px', padding: '6px 10px', background: '#fff8e1', border: '1px solid #ffe082', borderRadius: 6, color: '#e65100', fontSize: 11, flexShrink: 0 }}>
          {warning}
        </div>
      )}

      {/* Scrollable content */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '10px 14px' }}>

        {!state.investigationId && !loading && (
          <div style={{ textAlign: 'center', color: '#bbb', padding: '40px 10px', fontSize: 12 }}>
            {layoutId ? 'Click Scan or Full to start analysis.' : 'No layout saved. Save the layout first.'}
          </div>
        )}

        {/* ── Observations (Phase 1) ── */}
        {state.observations.length > 0 && (
          <section style={{ marginBottom: 14 }}>
            <SectionLabel>Observations ({positiveObs.length} detected / {state.observations.length} total)</SectionLabel>
            {positiveObs.map(obs => (
              <ObservationRow
                key={obs.id} obs={obs}
                expanded={expandedObsId === obs.id}
                onToggle={() => setExpandedObsId(expandedObsId === obs.id ? null : obs.id)}
              />
            ))}
            {negativeObs.length > 0 && (
              <div style={{ fontSize: 11, color: '#999', marginTop: 4 }}>
                Not detected: {negativeObs.map(o => o.skillKey).join(', ')}
              </div>
            )}
          </section>
        )}

        {/* ── Scenario text input ── */}
        {state.investigationId && (
          <section style={{ marginBottom: 14 }}>
            <SectionLabel>Scenario (optional)</SectionLabel>
            <textarea
              value={scenarioText}
              onChange={e => setScenarioText(e.target.value)}
              placeholder="Describe what you see or a trade thesis..."
              style={{
                width: '100%', minHeight: 50, padding: '6px 8px', border: '1px solid #ccc',
                borderRadius: 6, fontSize: 11, resize: 'vertical', fontFamily: 'inherit',
                boxSizing: 'border-box',
              }}
            />
          </section>
        )}

        {/* ── Flags ── */}
        {state.flags.length > 0 && (
          <section style={{ marginBottom: 14 }}>
            <SectionLabel>Pending Alerts ({state.flags.length})</SectionLabel>
            {state.flags.map(f => (
              <div key={f.id} style={{
                padding: '8px 10px', background: '#fff8e1', border: '1px solid #ffe082',
                borderRadius: 8, marginBottom: 6, fontSize: 11,
              }}>
                <div style={{ color: '#e65100', marginBottom: 4 }}>{f.flagText}</div>
                <div style={{ display: 'flex', gap: 6 }}>
                  <button onClick={() => handleAckFlag(f.id, 'ACCEPT')} style={smallBtn('#388e3c')}>Accept</button>
                  <button onClick={() => handleAckFlag(f.id, 'DISMISS')} style={smallBtn('#607d8b')}>Dismiss</button>
                </div>
              </div>
            ))}
          </section>
        )}

        {/* ── Hypotheses (Phase 2) ── */}
        {activeHypotheses.length > 0 && (
          <section style={{ marginBottom: 14 }}>
            <SectionLabel>Hypotheses ({activeHypotheses.length})</SectionLabel>
            {activeHypotheses.map(h => (
              <HypothesisRow
                key={h.id} h={h}
                expanded={expandedId === h.id}
                onToggle={() => setExpandedId(expandedId === h.id ? null : h.id)}
                onConfirm={handleConfirm}
                onDismiss={handleDismiss}
              />
            ))}
          </section>
        )}

        {tradeActive.length > 0 && (
          <section style={{ marginBottom: 14 }}>
            <SectionLabel>Trade Active ({tradeActive.length})</SectionLabel>
            {tradeActive.map(h => (
              <HypothesisRow
                key={h.id} h={h}
                expanded={expandedId === h.id}
                onToggle={() => setExpandedId(expandedId === h.id ? null : h.id)}
                onConfirm={handleConfirm}
                onDismiss={handleDismiss}
              />
            ))}
          </section>
        )}

        {state.trades.length > 0 && (
          <section style={{ marginBottom: 14 }}>
            <SectionLabel>Active Trades ({state.trades.length})</SectionLabel>
            {state.trades.map(t => (
              <div key={t.id} style={{
                padding: '7px 10px', background: '#f3f8ff', border: '1px solid #90caf9',
                borderRadius: 8, marginBottom: 6, fontSize: 11,
              }}>
                <div style={{ fontWeight: 600, color: '#1565c0' }}>
                  {t.entryType} Trade #{t.id}
                  {t.isOverrideTrade && <span style={{ marginLeft: 6, background: '#ff6f00', color: '#fff', borderRadius: 3, padding: '1px 4px', fontSize: 9 }}>OVERRIDE</span>}
                </div>
                <div style={{ color: '#555', marginTop: 2 }}>
                  {t.sl && `SL: ${t.sl}`}{t.tp && ` TP: ${t.tp}`}
                  {t.entryPrice && ` Entry: ${t.entryPrice}`}
                </div>
              </div>
            ))}
          </section>
        )}

        {inactive.length > 0 && (
          <section>
            <SectionLabel style={{ color: '#bbb' }}>Inactive ({inactive.length})</SectionLabel>
            {inactive.map(h => (
              <div key={h.id} style={{
                padding: '6px 10px', background: '#fafafa', border: '1px solid #eee',
                borderRadius: 8, marginBottom: 6, fontSize: 11, color: '#999',
              }}>
                <span style={{ fontWeight: 600 }}>{h.label}</span>
                <span style={{ marginLeft: 6, fontSize: 9, background: STATE_COLOR[h.state], color: '#fff', borderRadius: 3, padding: '1px 5px' }}>{h.state}</span>
                {h.invalidationReason && <div style={{ fontSize: 10, marginTop: 2, fontStyle: 'italic' }}>{h.invalidationReason}</div>}
              </div>
            ))}
          </section>
        )}

        {/* Elliott Advanced Analysis */}
        {(elliottResult !== null || elliottLoading || elliottError !== null) && (
          <div style={{ borderTop: '1px solid #333', marginTop: 8, paddingTop: 8 }}>
            <div style={{ fontWeight: 600, color: '#90caf9', fontSize: 13, padding: '0 12px 4px' }}>
              Elliott Analysis
            </div>
            <ElliottPanel
              result={elliottResult}
              loading={elliottLoading}
              error={elliottError}
            />
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function ObservationRow({ obs, expanded, onToggle }: {
  obs: CopilotObservation; expanded: boolean; onToggle: () => void;
}) {
  const conf = obs.confidence ?? 'LOW';
  return (
    <div style={{
      border: `1px solid ${obs.patternDetected ? (CONFIDENCE_COLOR[conf] ?? '#ccc') : '#eee'}`,
      borderRadius: 8, marginBottom: 6, background: obs.patternDetected ? '#fff' : '#fafafa',
    }}>
      <div onClick={onToggle} style={{
        padding: '7px 10px', cursor: 'pointer', display: 'flex',
        alignItems: 'center', justifyContent: 'space-between', gap: 6,
      }}>
        <div style={{ flex: 1 }}>
          <span style={{
            fontSize: 9, fontWeight: 700, background: CONFIDENCE_COLOR[conf] ?? '#999',
            color: '#fff', borderRadius: 3, padding: '1px 5px', marginRight: 6,
          }}>{conf}</span>
          <span style={{ fontSize: 12, fontWeight: 600, color: obs.patternDetected ? '#222' : '#999' }}>
            {obs.patternType ?? obs.skillKey}
          </span>
          {obs.stage && <span style={{ fontSize: 10, color: '#777', marginLeft: 6 }}>{obs.stage}</span>}
          {obs.timeframe && <span style={{ fontSize: 10, color: '#aaa', marginLeft: 4 }}>{obs.timeframe}</span>}
        </div>
        <span style={{ color: '#bbb', fontSize: 12, flexShrink: 0 }}>{expanded ? '-' : '+'}</span>
      </div>

      {expanded && (
        <div style={{ padding: '0 10px 8px', fontSize: 11 }}>
          {obs.structuralDetails && (
            <div style={{ color: '#444', marginBottom: 4 }}>{obs.structuralDetails}</div>
          )}
          {obs.reasoning && (
            <div style={{ color: '#666', marginBottom: 4, fontStyle: 'italic' }}>{obs.reasoning}</div>
          )}
          {obs.contradictions && obs.contradictions !== 'null' && obs.contradictions !== '[]' && (
            <div style={{ color: '#c62828', fontSize: 10 }}>
              Contradictions: {obs.contradictions}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function HypothesisRow({
  h, expanded, onToggle, onConfirm, onDismiss,
}: {
  h: CopilotHypothesis; expanded: boolean; onToggle: () => void;
  onConfirm: (id: number, entryType: string) => void;
  onDismiss: (id: number) => void;
}) {
  const canAct = h.state === 'WATCHING' || h.state === 'BUILDING' || h.state === 'CONFIRMED';
  return (
    <div style={{
      border: `1px solid ${STATE_COLOR[h.state] ?? '#ccc'}`,
      borderRadius: 8, marginBottom: 8, background: '#fff',
    }}>
      <div onClick={onToggle} style={{
        padding: '8px 10px', cursor: 'pointer', display: 'flex',
        alignItems: 'flex-start', justifyContent: 'space-between', gap: 6,
      }}>
        <div style={{ flex: 1 }}>
          <span style={{
            fontSize: 9, fontWeight: 700, letterSpacing: 1, textTransform: 'uppercase',
            background: STATE_COLOR[h.state], color: '#fff', borderRadius: 3, padding: '1px 5px',
            marginRight: 6,
          }}>{h.state}</span>
          <span style={{ fontSize: 12, fontWeight: 700, color: '#222' }}>{h.label}</span>
          <div style={{ fontSize: 10, color: '#777', marginTop: 2 }}>{h.pattern} · {h.stage}</div>
        </div>
        <span style={{ color: '#bbb', fontSize: 12, flexShrink: 0 }}>{expanded ? '-' : '+'}</span>
      </div>

      {expanded && (
        <div style={{ padding: '0 10px 8px', fontSize: 11 }}>
          {h.confidenceLayers && (
            <div style={{ marginBottom: 4 }}>
              <strong style={{ color: '#555' }}>Confidence:</strong>
              <div style={{ color: '#444', whiteSpace: 'pre-wrap', marginTop: 2 }}>{h.confidenceLayers}</div>
            </div>
          )}
          {h.invalidationConditions && (
            <div style={{ marginBottom: 4 }}>
              <strong style={{ color: '#c62828' }}>Invalidation:</strong>
              <div style={{ color: '#c62828', whiteSpace: 'pre-wrap', marginTop: 2 }}>{h.invalidationConditions}</div>
            </div>
          )}
          {h.waveContext && <div style={{ color: '#777', marginBottom: 4 }}><strong>Wave:</strong> {h.waveContext}</div>}
        </div>
      )}

      {canAct && (
        <div style={{ padding: '0 10px 8px', display: 'flex', gap: 6 }}>
          <button onClick={() => onConfirm(h.id, 'ANTICIPATORY')} style={smallBtn('#388e3c')}>Anticipatory</button>
          <button onClick={() => onConfirm(h.id, 'CONFIRMATION')} style={smallBtn('#1976d2')}>Confirmation</button>
          <button onClick={() => onDismiss(h.id)} style={smallBtn('#c62828')}>x</button>
        </div>
      )}
    </div>
  );
}

function SectionLabel({ children, style }: { children: React.ReactNode; style?: React.CSSProperties }) {
  return (
    <div style={{ fontSize: 10, fontWeight: 700, color: '#555', letterSpacing: 0.5, marginBottom: 6, textTransform: 'uppercase', ...style }}>
      {children}
    </div>
  );
}

function actionBtnStyle(isLoading: boolean, color: string): React.CSSProperties {
  return {
    flex: 1, padding: '6px 10px',
    background: isLoading ? '#bbb' : color,
    color: '#fff', border: 'none', borderRadius: 5,
    cursor: isLoading ? 'default' : 'pointer',
    fontWeight: 700, fontSize: 11,
  };
}

function smallBtn(bg: string): React.CSSProperties {
  return {
    padding: '3px 8px', background: bg, color: '#fff', border: 'none',
    borderRadius: 5, cursor: 'pointer', fontSize: 10, fontWeight: 600,
  };
}

const miniBtn: React.CSSProperties = {
  padding: '3px 8px', background: 'rgba(255,255,255,0.15)', color: '#fff',
  border: '1px solid rgba(255,255,255,0.3)', borderRadius: 5, cursor: 'pointer', fontSize: 11,
};
