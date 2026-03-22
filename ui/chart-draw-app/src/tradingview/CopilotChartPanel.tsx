import React, { useState, useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import type { CopilotHypothesis, CopilotAnomalyFlag, CopilotActiveTrade } from './copilotTypes';
import {
  triggerAnalysis, getHypothesisBoard, getTradeDashboard,
  confirmHypothesis, dismissHypothesis, acknowledgeFlag,
} from './copilotApi';

export interface CopilotPanelState {
  investigationId: number | null;
  hypotheses: CopilotHypothesis[];
  flags: CopilotAnomalyFlag[];
  trades: CopilotActiveTrade[];
}

interface Props {
  open: boolean;
  onClose: () => void;
  symbol: string;
  timeframe: string;
  layoutId: number | null;
  getChartState?: () => string;
  /** Called when new hypotheses are loaded — parent can draw annotations */
  onHypothesesLoaded?: (hypotheses: CopilotHypothesis[], investigationId: number) => void;
}

const STATE_COLOR: Record<string, string> = {
  WATCHING:    '#607d8b',
  BUILDING:    '#f57c00',
  CONFIRMED:   '#388e3c',
  TRADE_ACTIVE:'#1976d2',
  INVALIDATED: '#c62828',
  EXPIRED:     '#9e9e9e',
};

export default function CopilotChartPanel({
  open, onClose, symbol, timeframe, layoutId, getChartState, onHypothesesLoaded,
}: Props) {
  const navigate = useNavigate();
  const [state, setState] = useState<CopilotPanelState>({
    investigationId: null,
    hypotheses: [],
    flags: [],
    trades: [],
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<number | null>(null);

  const refresh = useCallback(async (investigationId: number) => {
    const [board, dashboard] = await Promise.all([
      getHypothesisBoard(investigationId),
      getTradeDashboard(investigationId),
    ]);
    const newState: CopilotPanelState = {
      investigationId,
      hypotheses: board.hypotheses,
      flags: board.unacknowledgedFlags,
      trades: dashboard.activeTrades,
    };
    setState(newState);
    onHypothesesLoaded?.(board.hypotheses, investigationId);
  }, [onHypothesesLoaded]);

  const trigger = useCallback(async (force = false) => {
    if (!layoutId) { setError('No layout ID available. Save the layout first.'); return; }
    setLoading(true); setError(null); setWarning(null);
    try {
      let drawingsJson: string | undefined;
      try { drawingsJson = getChartState?.(); } catch { /* ignore */ }
      const res = await triggerAnalysis(layoutId, symbol, drawingsJson, [timeframe], force);
      if (res.warning) setWarning(res.warning);
      await refresh(res.investigationId);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  }, [layoutId, symbol, timeframe, getChartState, refresh]);

  // Auto-trigger when panel opens — reuse existing investigation if within validity window
  useEffect(() => {
    if (open && layoutId && !loading) {
      trigger(false);
    }
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

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

  const activeHypotheses = state.hypotheses.filter(h =>
    h.state === 'WATCHING' || h.state === 'BUILDING' || h.state === 'CONFIRMED'
  );
  const tradeActive = state.hypotheses.filter(h => h.state === 'TRADE_ACTIVE');
  const inactive = state.hypotheses.filter(h => h.state === 'INVALIDATED' || h.state === 'EXPIRED');

  return (
    <>
      {/* Sliding panel */}
      <div style={{
        position: 'fixed',
        top: 0,
        right: open ? 0 : '-420px',
        width: 400,
        height: '100vh',
        background: '#fff',
        boxShadow: '-4px 0 20px rgba(0,0,0,0.12)',
        zIndex: 9997,
        display: 'flex',
        flexDirection: 'column',
        transition: 'right 0.3s ease',
        overflow: 'hidden',
      }}>
        {/* Header */}
        <div style={{
          background: '#1a237e', color: '#fff', padding: '12px 16px',
          display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0,
        }}>
          <div>
            <div style={{ fontWeight: 700, fontSize: 15 }}>🧠 Co-Pilot</div>
            <div style={{ fontSize: 11, opacity: 0.7 }}>{symbol} · {timeframe}</div>
          </div>
          <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
            {state.investigationId && (
              <button
                onClick={() => navigate('/copilot')}
                style={miniBtn}
                title="Open full dashboard"
              >Full ↗</button>
            )}
            <button onClick={onClose} style={{ ...miniBtn, fontSize: 16 }}>×</button>
          </div>
        </div>

        {/* Trigger bar */}
        <div style={{
          padding: '10px 14px', background: '#f8f9ff', borderBottom: '1px solid #e8eaf6',
          display: 'flex', gap: 8, alignItems: 'center', flexShrink: 0,
        }}>
          <button
            onClick={() => trigger(true)}
            disabled={loading}
            style={{
              flex: 1, padding: '8px 14px', background: loading ? '#9fa8da' : '#3f51b5',
              color: '#fff', border: 'none', borderRadius: 6, cursor: loading ? 'default' : 'pointer',
              fontWeight: 700, fontSize: 13,
            }}
          >
            {loading ? 'Analysing…' : state.investigationId ? '↻ Re-trigger Analysis' : '▶ Trigger Analysis'}
          </button>
          {state.investigationId && (
            <button
              onClick={() => state.investigationId && refresh(state.investigationId)}
              style={{ ...miniBtn, padding: '8px 10px', background: '#fff', color: '#555', border: '1px solid #ccc', borderRadius: 6 }}
              title="Refresh"
            >↻</button>
          )}
        </div>

        {/* Investigation ID badge */}
        {state.investigationId && (
          <div style={{ padding: '6px 14px', background: '#e8eaf6', fontSize: 11, color: '#3f51b5', fontWeight: 600, flexShrink: 0 }}>
            Investigation #{state.investigationId}
          </div>
        )}

        {error && (
          <div style={{ margin: '10px 14px', padding: '8px 12px', background: '#ffebee', border: '1px solid #ef9a9a', borderRadius: 6, color: '#c62828', fontSize: 12, flexShrink: 0 }}>
            {error}
          </div>
        )}
        {warning && (
          <div style={{ margin: '10px 14px', padding: '8px 12px', background: '#fff8e1', border: '1px solid #ffe082', borderRadius: 6, color: '#e65100', fontSize: 12, flexShrink: 0 }}>
            ⚠ {warning}
          </div>
        )}

        {/* Scrollable content */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '12px 14px' }}>

          {!state.investigationId && !loading && (
            <div style={{ textAlign: 'center', color: '#bbb', padding: '40px 10px', fontSize: 13 }}>
              {layoutId ? 'Starting analysis…' : 'No layout saved. Save the layout first, then re-open Co-Pilot.'}
            </div>
          )}

          {/* Flags */}
          {state.flags.length > 0 && (
            <section style={{ marginBottom: 14 }}>
              <SectionLabel>⚠ Pending Alerts ({state.flags.length})</SectionLabel>
              {state.flags.map(f => (
                <div key={f.id} style={{
                  padding: '9px 11px', background: '#fff8e1', border: '1px solid #ffe082',
                  borderRadius: 8, marginBottom: 6, fontSize: 12,
                }}>
                  <div style={{ color: '#e65100', marginBottom: 6 }}>{f.flagText}</div>
                  <div style={{ display: 'flex', gap: 6 }}>
                    <button onClick={() => handleAckFlag(f.id, 'ACCEPT')} style={smallBtn('#388e3c')}>Accept</button>
                    <button onClick={() => handleAckFlag(f.id, 'DISMISS')} style={smallBtn('#607d8b')}>Dismiss</button>
                  </div>
                </div>
              ))}
            </section>
          )}

          {/* Active hypotheses */}
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

          {/* Trade-active */}
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

          {/* Active trades summary */}
          {state.trades.length > 0 && (
            <section style={{ marginBottom: 14 }}>
              <SectionLabel>Active Trades ({state.trades.length})</SectionLabel>
              {state.trades.map(t => (
                <div key={t.id} style={{
                  padding: '8px 11px', background: '#f3f8ff', border: '1px solid #90caf9',
                  borderRadius: 8, marginBottom: 6, fontSize: 12,
                }}>
                  <div style={{ fontWeight: 600, color: '#1565c0' }}>
                    {t.entryType} Trade #{t.id}
                    {t.isOverrideTrade && <span style={{ marginLeft: 6, background: '#ff6f00', color: '#fff', borderRadius: 3, padding: '1px 4px', fontSize: 10 }}>OVERRIDE</span>}
                  </div>
                  <div style={{ color: '#555', marginTop: 2 }}>
                    {t.sl && `SL: ${t.sl}`}{t.tp && ` · TP: ${t.tp}`}
                    {t.entryPrice && ` · Entry: ${t.entryPrice}`}
                  </div>
                </div>
              ))}
            </section>
          )}

          {/* Inactive */}
          {inactive.length > 0 && (
            <section>
              <SectionLabel style={{ color: '#bbb' }}>Inactive ({inactive.length})</SectionLabel>
              {inactive.map(h => (
                <div key={h.id} style={{
                  padding: '7px 11px', background: '#fafafa', border: '1px solid #eee',
                  borderRadius: 8, marginBottom: 6, fontSize: 12, color: '#999',
                }}>
                  <span style={{ fontWeight: 600 }}>{h.label}</span>
                  <span style={{ marginLeft: 8, fontSize: 10, background: STATE_COLOR[h.state], color: '#fff', borderRadius: 3, padding: '1px 5px' }}>{h.state}</span>
                  {h.invalidationReason && <div style={{ fontSize: 11, marginTop: 3, fontStyle: 'italic' }}>{h.invalidationReason}</div>}
                </div>
              ))}
            </section>
          )}
        </div>
      </div>
    </>
  );
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function HypothesisRow({
  h, expanded, onToggle, onConfirm, onDismiss,
}: {
  h: CopilotHypothesis;
  expanded: boolean;
  onToggle: () => void;
  onConfirm: (id: number, entryType: string) => void;
  onDismiss: (id: number) => void;
}) {
  const canAct = h.state === 'WATCHING' || h.state === 'BUILDING' || h.state === 'CONFIRMED';
  return (
    <div style={{
      border: `1px solid ${STATE_COLOR[h.state] ?? '#ccc'}`,
      borderRadius: 8, marginBottom: 8,
      background: '#fff',
    }}>
      <div
        onClick={onToggle}
        style={{ padding: '9px 11px', cursor: 'pointer', display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 6 }}
      >
        <div style={{ flex: 1 }}>
          <span style={{
            fontSize: 9, fontWeight: 700, letterSpacing: 1, textTransform: 'uppercase',
            background: STATE_COLOR[h.state], color: '#fff', borderRadius: 3, padding: '1px 5px',
            marginRight: 6,
          }}>{h.state}</span>
          <span style={{ fontSize: 13, fontWeight: 700, color: '#222' }}>{h.label}</span>
          <div style={{ fontSize: 11, color: '#777', marginTop: 2 }}>{h.pattern} · {h.direction} · {h.stage}</div>
        </div>
        <span style={{ color: '#bbb', fontSize: 14, flexShrink: 0 }}>{expanded ? '▲' : '▼'}</span>
      </div>

      {expanded && (
        <div style={{ padding: '0 11px 10px', fontSize: 12 }}>
          {h.confidenceLayers && (
            <div style={{ marginBottom: 6 }}>
              <strong style={{ color: '#555' }}>Confidence:</strong>
              <div style={{ color: '#444', whiteSpace: 'pre-wrap', marginTop: 2 }}>{h.confidenceLayers}</div>
            </div>
          )}
          {h.invalidationConditions && (
            <div style={{ marginBottom: 6 }}>
              <strong style={{ color: '#c62828' }}>Invalidation:</strong>
              <div style={{ color: '#c62828', whiteSpace: 'pre-wrap', marginTop: 2 }}>{h.invalidationConditions}</div>
            </div>
          )}
          {h.waveContext && <div style={{ color: '#777', marginBottom: 6 }}><strong>Wave:</strong> {h.waveContext}</div>}
        </div>
      )}

      {canAct && (
        <div style={{ padding: '0 11px 10px', display: 'flex', gap: 6 }}>
          <button onClick={() => onConfirm(h.id, 'ANTICIPATORY')} style={smallBtn('#388e3c')}>✓ Anticipatory</button>
          <button onClick={() => onConfirm(h.id, 'CONFIRMATION')} style={smallBtn('#1976d2')}>✓ Confirmation</button>
          <button onClick={() => onDismiss(h.id)} style={smallBtn('#c62828')}>✗</button>
        </div>
      )}
    </div>
  );
}

function SectionLabel({ children, style }: { children: React.ReactNode; style?: React.CSSProperties }) {
  return (
    <div style={{ fontSize: 11, fontWeight: 700, color: '#555', letterSpacing: 0.5, marginBottom: 6, textTransform: 'uppercase', ...style }}>
      {children}
    </div>
  );
}

function smallBtn(bg: string): React.CSSProperties {
  return {
    padding: '4px 9px', background: bg, color: '#fff', border: 'none',
    borderRadius: 5, cursor: 'pointer', fontSize: 11, fontWeight: 600,
  };
}

const miniBtn: React.CSSProperties = {
  padding: '4px 9px', background: 'rgba(255,255,255,0.15)', color: '#fff',
  border: '1px solid rgba(255,255,255,0.3)', borderRadius: 5, cursor: 'pointer', fontSize: 12,
};
