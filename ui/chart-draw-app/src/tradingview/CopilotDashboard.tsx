import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import type { CopilotHypothesis, CopilotActiveTrade, CopilotAnomalyFlag } from './copilotTypes';
import { getTradeDashboard, getHypothesisBoard, confirmHypothesis, dismissHypothesis, acknowledgeFlag, openTrade, closeTrade } from './copilotApi';

// ─── Colour maps ──────────────────────────────────────────────────────────────

const STATE_COLOR: Record<string, string> = {
  WATCHING: '#607d8b',
  BUILDING: '#f57c00',
  CONFIRMED: '#388e3c',
  TRADE_ACTIVE: '#1976d2',
  INVALIDATED: '#c62828',
  EXPIRED: '#9e9e9e',
};

// ─── Sub-components ───────────────────────────────────────────────────────────

function HypothesisCard({
  h,
  onConfirm,
  onDismiss,
}: {
  h: CopilotHypothesis;
  onConfirm: (id: number, entryType: string) => void;
  onDismiss: (id: number) => void;
}) {
  const [open, setOpen] = useState(false);
  const canAct = h.state === 'WATCHING' || h.state === 'BUILDING' || h.state === 'CONFIRMED';

  return (
    <div style={{
      border: `1px solid ${STATE_COLOR[h.state] ?? '#ccc'}`,
      borderRadius: 10,
      padding: '12px 14px',
      background: '#fff',
      boxShadow: '0 1px 4px rgba(0,0,0,0.08)',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div style={{ flex: 1 }}>
          <span style={{
            display: 'inline-block', fontSize: 10, fontWeight: 700, letterSpacing: 1,
            background: STATE_COLOR[h.state] ?? '#ccc', color: '#fff',
            borderRadius: 4, padding: '2px 6px', marginBottom: 4,
          }}>{h.state}</span>
          <div style={{ fontWeight: 700, fontSize: 14, color: '#222' }}>{h.label}</div>
          <div style={{ fontSize: 12, color: '#666', marginTop: 2 }}>
            {h.pattern} · {h.direction} · {h.stage}
          </div>
        </div>
        <button
          onClick={() => setOpen(!open)}
          style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: 16, color: '#999' }}
        >{open ? '▲' : '▼'}</button>
      </div>

      {open && (
        <div style={{ marginTop: 10, fontSize: 12, color: '#444', display: 'flex', flexDirection: 'column', gap: 6 }}>
          {h.confidenceLayers && (
            <div>
              <strong>Confidence Layers:</strong>
              <div style={{ whiteSpace: 'pre-wrap', color: '#555' }}>{h.confidenceLayers}</div>
            </div>
          )}
          {h.invalidationConditions && (
            <div>
              <strong>Invalidation:</strong>
              <div style={{ whiteSpace: 'pre-wrap', color: '#c62828' }}>{h.invalidationConditions}</div>
            </div>
          )}
          {h.waveContext && (
            <div><strong>Wave Context:</strong> {h.waveContext}</div>
          )}
          {h.invalidationReason && (
            <div style={{ color: '#c62828' }}><strong>Reason:</strong> {h.invalidationReason}</div>
          )}
        </div>
      )}

      {canAct && (
        <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
          <button
            onClick={() => onConfirm(h.id, 'ANTICIPATORY')}
            style={actionBtn('#388e3c')}
          >✓ Anticipatory</button>
          <button
            onClick={() => onConfirm(h.id, 'CONFIRMATION')}
            style={actionBtn('#1976d2')}
          >✓ Confirmation</button>
          <button
            onClick={() => onDismiss(h.id)}
            style={actionBtn('#c62828')}
          >✗ Dismiss</button>
        </div>
      )}
    </div>
  );
}

function TradeCard({
  trade,
  onOpen,
  onClose,
}: {
  trade: CopilotActiveTrade;
  onOpen: (id: number, price: number) => void;
  onClose: (id: number, price: number, outcome: string) => void;
}) {
  const [entryPrice, setEntryPrice] = useState('');
  const [closePrice, setClosePrice] = useState('');

  return (
    <div style={{
      border: '1px solid #1976d2',
      borderRadius: 10,
      padding: '12px 14px',
      background: trade.isOverrideTrade ? '#fff8e1' : '#fff',
      boxShadow: '0 1px 4px rgba(0,0,0,0.08)',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <span style={{ fontSize: 11, fontWeight: 700, color: '#1976d2' }}>{trade.entryType}</span>
          {trade.isOverrideTrade && (
            <span style={{ fontSize: 10, marginLeft: 6, background: '#ff6f00', color: '#fff', borderRadius: 3, padding: '1px 5px' }}>OVERRIDE</span>
          )}
          <div style={{ fontSize: 13, fontWeight: 600, color: '#222', marginTop: 2 }}>
            Trade #{trade.id}
          </div>
          <div style={{ fontSize: 11, color: '#666' }}>
            {trade.sl && `SL: ${trade.sl}`} {trade.tp && `· TP: ${trade.tp}`}
          </div>
        </div>
        <span style={{
          fontSize: 10, fontWeight: 700, letterSpacing: 1,
          background: trade.status === 'OPEN' ? '#1976d2' : '#9e9e9e',
          color: '#fff', borderRadius: 4, padding: '2px 6px',
        }}>{trade.status}</span>
      </div>

      {trade.isOverrideTrade && trade.systemObjection && (
        <div style={{ fontSize: 11, color: '#e65100', marginTop: 6, fontStyle: 'italic' }}>
          ⚠ {trade.systemObjection}
        </div>
      )}

      {/* Not yet entered */}
      {!trade.entryPrice && (
        <div style={{ display: 'flex', gap: 6, marginTop: 10, alignItems: 'center' }}>
          <input
            type="number"
            placeholder="Entry price"
            value={entryPrice}
            onChange={e => setEntryPrice(e.target.value)}
            style={inputStyle}
          />
          <button
            onClick={() => entryPrice && onOpen(trade.id, Number(entryPrice))}
            style={actionBtn('#1976d2')}
          >Enter</button>
        </div>
      )}

      {/* Entered, not yet closed */}
      {trade.entryPrice && trade.status === 'OPEN' && (
        <div style={{ fontSize: 12, color: '#333', marginTop: 6 }}>
          <strong>Entry:</strong> {trade.entryPrice}
          <div style={{ display: 'flex', gap: 6, marginTop: 8, alignItems: 'center' }}>
            <input
              type="number"
              placeholder="Close price"
              value={closePrice}
              onChange={e => setClosePrice(e.target.value)}
              style={inputStyle}
            />
            <button onClick={() => closePrice && onClose(trade.id, Number(closePrice), 'WIN')} style={actionBtn('#388e3c')}>Win</button>
            <button onClick={() => closePrice && onClose(trade.id, Number(closePrice), 'LOSS')} style={actionBtn('#c62828')}>Loss</button>
          </div>
        </div>
      )}

      {/* Closed */}
      {trade.status !== 'OPEN' && trade.entryPrice && (
        <div style={{ fontSize: 12, color: '#555', marginTop: 6 }}>
          <strong>Entry:</strong> {trade.entryPrice} → <strong>Close:</strong> {trade.closePrice ?? '—'}
          <span style={{ marginLeft: 8, fontWeight: 700, color: trade.outcome === 'WIN' ? '#388e3c' : '#c62828' }}>
            {trade.outcome}
          </span>
        </div>
      )}
    </div>
  );
}

function FlagCard({ flag, onAck }: { flag: CopilotAnomalyFlag; onAck: (id: number, action: string) => void }) {
  return (
    <div style={{
      border: '1px solid #f57c00',
      borderRadius: 8,
      padding: '10px 12px',
      background: '#fff8e1',
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'flex-start',
      gap: 10,
    }}>
      <div style={{ fontSize: 12, color: '#444', flex: 1 }}>
        <strong style={{ color: '#e65100' }}>⚠ Alert:</strong> {flag.flagText}
      </div>
      <div style={{ display: 'flex', gap: 4, flexShrink: 0 }}>
        <button onClick={() => onAck(flag.id, 'ACCEPT')} style={actionBtn('#388e3c')}>Accept</button>
        <button onClick={() => onAck(flag.id, 'DISMISS')} style={actionBtn('#607d8b')}>Dismiss</button>
      </div>
    </div>
  );
}

// ─── Main Dashboard ───────────────────────────────────────────────────────────

export default function CopilotDashboard() {
  const navigate = useNavigate();
  const [investigationId, setInvestigationId] = useState<number | null>(() => {
    const s = localStorage.getItem('copilot_investigation_id');
    return s ? Number(s) : null;
  });
  const [inputId, setInputId] = useState('');
  const [hypotheses, setHypotheses] = useState<CopilotHypothesis[]>([]);
  const [flags, setFlags] = useState<CopilotAnomalyFlag[]>([]);
  const [trades, setTrades] = useState<CopilotActiveTrade[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async (id: number) => {
    setLoading(true);
    setError(null);
    try {
      const [board, dashboard] = await Promise.all([
        getHypothesisBoard(id),
        getTradeDashboard(id),
      ]);
      setHypotheses(board.hypotheses);
      setFlags(board.unacknowledgedFlags);
      setTrades(dashboard.activeTrades);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (investigationId) load(investigationId);
  }, [investigationId]);

  const handleSetId = () => {
    const id = Number(inputId);
    if (!id) return;
    localStorage.setItem('copilot_investigation_id', String(id));
    setInvestigationId(id);
  };

  const handleConfirm = async (hypothesisId: number, entryType: string) => {
    try {
      await confirmHypothesis(hypothesisId, entryType, false);
      if (investigationId) await load(investigationId);
    } catch (e) {
      setError(String(e));
    }
  };

  const handleDismiss = async (hypothesisId: number) => {
    try {
      await dismissHypothesis(hypothesisId);
      if (investigationId) await load(investigationId);
    } catch (e) {
      setError(String(e));
    }
  };

  const handleAckFlag = async (flagId: number, action: string) => {
    try {
      await acknowledgeFlag(flagId, action);
      if (investigationId) await load(investigationId);
    } catch (e) {
      setError(String(e));
    }
  };

  const handleOpenTrade = async (tradeId: number, price: number) => {
    try {
      await openTrade(tradeId, price);
      if (investigationId) await load(investigationId);
    } catch (e) {
      setError(String(e));
    }
  };

  const handleCloseTrade = async (tradeId: number, price: number, outcome: string) => {
    try {
      await closeTrade(tradeId, price, outcome);
      if (investigationId) await load(investigationId);
    } catch (e) {
      setError(String(e));
    }
  };

  const watchingOrBuilding = hypotheses.filter(h => h.state === 'WATCHING' || h.state === 'BUILDING');
  const confirmed = hypotheses.filter(h => h.state === 'CONFIRMED');
  const tradeActive = hypotheses.filter(h => h.state === 'TRADE_ACTIVE');
  const inactive = hypotheses.filter(h => h.state === 'INVALIDATED' || h.state === 'EXPIRED');

  return (
    <div style={{ minHeight: '100vh', background: '#f5f5f5', padding: 24 }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
        <div>
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 700, color: '#222' }}>Co-Pilot Dashboard</h1>
          <div style={{ fontSize: 12, color: '#888', marginTop: 2 }}>
            {investigationId ? `Investigation #${investigationId}` : 'No active investigation'}
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <button onClick={() => navigate('/skills')} style={navBtn}>🧠 Skills</button>
          <button onClick={() => navigate('/chart')} style={navBtn}>📈 Chart</button>
          <button onClick={() => navigate('/')} style={navBtn}>← Dashboard</button>
        </div>
      </div>

      {/* Investigation ID input */}
      <div style={{
        background: '#fff', borderRadius: 10, padding: '14px 16px', marginBottom: 20,
        boxShadow: '0 1px 4px rgba(0,0,0,0.08)',
        display: 'flex', gap: 10, alignItems: 'center',
      }}>
        <label style={{ fontSize: 13, color: '#555', fontWeight: 600 }}>Investigation ID:</label>
        <input
          type="number"
          placeholder="Enter investigation ID from chart trigger"
          value={inputId}
          onChange={e => setInputId(e.target.value)}
          style={{ ...inputStyle, width: 260 }}
        />
        <button onClick={handleSetId} style={actionBtn('#1976d2')}>Load</button>
        {investigationId && (
          <button onClick={() => investigationId && load(investigationId)} style={actionBtn('#607d8b')}>
            ↻ Refresh
          </button>
        )}
      </div>

      {error && (
        <div style={{ background: '#ffebee', border: '1px solid #ef9a9a', borderRadius: 8, padding: '10px 14px', marginBottom: 16, color: '#c62828', fontSize: 13 }}>
          {error}
        </div>
      )}

      {loading && (
        <div style={{ textAlign: 'center', padding: 40, color: '#888' }}>Loading investigation…</div>
      )}

      {!loading && investigationId && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>

          {/* ── Left column: Hypotheses ── */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

            {/* Flags */}
            {flags.length > 0 && (
              <section>
                <SectionHeader icon="⚠" title={`Pending Alerts (${flags.length})`} />
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {flags.map(f => (
                    <FlagCard key={f.id} flag={f} onAck={handleAckFlag} />
                  ))}
                </div>
              </section>
            )}

            {/* Watching / Building */}
            {watchingOrBuilding.length > 0 && (
              <section>
                <SectionHeader icon="👁" title={`Watching / Building (${watchingOrBuilding.length})`} />
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {watchingOrBuilding.map(h => (
                    <HypothesisCard key={h.id} h={h} onConfirm={handleConfirm} onDismiss={handleDismiss} />
                  ))}
                </div>
              </section>
            )}

            {/* Confirmed */}
            {confirmed.length > 0 && (
              <section>
                <SectionHeader icon="✓" title={`Confirmed (${confirmed.length})`} />
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {confirmed.map(h => (
                    <HypothesisCard key={h.id} h={h} onConfirm={handleConfirm} onDismiss={handleDismiss} />
                  ))}
                </div>
              </section>
            )}

            {/* Inactive */}
            {inactive.length > 0 && (
              <section>
                <SectionHeader icon="✗" title={`Invalidated / Expired (${inactive.length})`} />
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {inactive.map(h => (
                    <HypothesisCard key={h.id} h={h} onConfirm={handleConfirm} onDismiss={handleDismiss} />
                  ))}
                </div>
              </section>
            )}

            {hypotheses.length === 0 && !loading && (
              <div style={{ textAlign: 'center', padding: 40, color: '#aaa', fontSize: 14 }}>
                No hypotheses yet. Trigger an investigation from the chart.
              </div>
            )}
          </div>

          {/* ── Right column: Trades ── */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

            {tradeActive.length > 0 && (
              <section>
                <SectionHeader icon="💼" title={`Trade-Active Hypotheses (${tradeActive.length})`} />
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {tradeActive.map(h => (
                    <HypothesisCard key={h.id} h={h} onConfirm={handleConfirm} onDismiss={handleDismiss} />
                  ))}
                </div>
              </section>
            )}

            {trades.length > 0 && (
              <section>
                <SectionHeader icon="📊" title={`Active Trades (${trades.length})`} />
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {trades.map(t => (
                    <TradeCard
                      key={t.id}
                      trade={t}
                      onOpen={handleOpenTrade}
                      onClose={handleCloseTrade}
                    />
                  ))}
                </div>
              </section>
            )}

            {trades.length === 0 && tradeActive.length === 0 && !loading && (
              <div style={{ textAlign: 'center', padding: 40, color: '#aaa', fontSize: 14 }}>
                No active trades. Confirm a hypothesis to create a trade.
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function SectionHeader({ icon, title }: { icon: string; title: string }) {
  return (
    <h3 style={{ margin: '0 0 10px', fontSize: 14, fontWeight: 700, color: '#444', display: 'flex', alignItems: 'center', gap: 6 }}>
      <span>{icon}</span> {title}
    </h3>
  );
}

function actionBtn(bg: string): React.CSSProperties {
  return {
    padding: '5px 12px', background: bg, color: '#fff', border: 'none',
    borderRadius: 6, cursor: 'pointer', fontSize: 12, fontWeight: 600, whiteSpace: 'nowrap',
  };
}

const navBtn: React.CSSProperties = {
  padding: '6px 14px', background: '#fff', color: '#444', border: '1px solid #ddd',
  borderRadius: 8, cursor: 'pointer', fontSize: 13, fontWeight: 500,
};

const inputStyle: React.CSSProperties = {
  border: '1px solid #ddd', borderRadius: 6, padding: '5px 10px',
  fontSize: 13, outline: 'none',
};
