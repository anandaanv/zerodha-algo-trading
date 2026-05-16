import { useEffect, useState } from 'react';
import {
  AnnotationIntent, DrawingAnnotation, INTENT_LABELS, INTENT_ORDER,
} from './types';

interface Props {
  tabUuid: string;
  symbol: string;
  interval?: string;
  initial?: DrawingAnnotation | null;   // null = new
  onClose: () => void;
  onSave: (payload: {
    drawingId?: string;
    intent: AnnotationIntent;
    intentParamsJson?: string;
    geometryJson?: string;
    note?: string;
    weight: number;
  }) => Promise<void>;
}

type Params = Record<string, unknown>;

function parseParams(raw?: string): Params {
  if (!raw) return {};
  try { return JSON.parse(raw); } catch { return {}; }
}

export default function AnnotationFormModal({
  tabUuid: _tabUuid, symbol, interval: _interval, initial, onClose, onSave,
}: Props) {
  const [intent, setIntent] = useState<AnnotationIntent>(initial?.intent || 'KEY_LEVEL');
  const [note, setNote] = useState<string>(initial?.note || '');
  const [weight, setWeight] = useState<number>(initial?.weight ?? 3);
  const [drawingId, setDrawingId] = useState<string>(initial?.drawingId || '');
  const [geometry, setGeometry] = useState<string>(initial?.geometryJson || '');
  const [params, setParams] = useState<Params>(parseParams(initial?.intentParamsJson));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Reset params to sensible defaults when intent changes (only for new annotations)
    if (initial) return;
    if (intent === 'ABC_PROJECTION') setParams({ ratios: [1.0, 1.272, 1.618] });
    else if (intent === 'RETEST_ENTRY') setParams({ tolerance_pct: 0.5, side: 'either' });
    else if (intent === 'BREAKOUT_CONFIRM') setParams({ confirmation_bars: 2, side: 'above' });
    else if (intent === 'OVERTHROW_WATCH') setParams({ max_overthrow_pct: 1.5, expected_settle_bars: 6 });
    else if (intent === 'REJECT_ON_TOUCH') setParams({ tolerance_pct: 0.3 });
    else if (intent === 'INVALIDATION' || intent === 'TARGET') setParams({ side: 'above' });
    else setParams({});
  }, [intent, initial]);

  async function handleSave() {
    setSaving(true);
    setError(null);
    try {
      await onSave({
        drawingId: drawingId || undefined,
        intent,
        intentParamsJson: Object.keys(params).length ? JSON.stringify(params) : undefined,
        geometryJson: geometry || undefined,
        note: note || undefined,
        weight,
      });
      onClose();
    } catch (e: any) {
      setError(e?.message || 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  function updateParam(key: string, value: unknown) {
    setParams(p => ({ ...p, [key]: value }));
  }

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={e => e.stopPropagation()}>
        <div style={header}>
          <h3 style={{ margin: 0, fontSize: 15 }}>
            {initial ? 'Edit annotation' : 'New annotation'} · {symbol}
          </h3>
          <button onClick={onClose} style={iconBtn}>✕</button>
        </div>

        <div style={body}>
          {error && <div style={errorBox}>{error}</div>}

          <Field label="Intent">
            <select value={intent} onChange={e => setIntent(e.target.value as AnnotationIntent)} style={input}>
              {INTENT_ORDER.map(i => <option key={i} value={i}>{INTENT_LABELS[i]}</option>)}
            </select>
          </Field>

          <Field label="Drawing ID (auto-generated if blank)">
            <input
              value={drawingId}
              onChange={e => setDrawingId(e.target.value)}
              placeholder="e.g. tv-shape-1234 or blank"
              style={input}
            />
          </Field>

          {/* Intent-specific param fields */}
          {intent === 'RETEST_ENTRY' && (
            <>
              <Field label="Tolerance %">
                <input type="number" step="0.1" value={String(params.tolerance_pct ?? '')}
                  onChange={e => updateParam('tolerance_pct', Number(e.target.value))} style={input} />
              </Field>
              <Field label="Side">
                <select value={String(params.side ?? 'either')} onChange={e => updateParam('side', e.target.value)} style={input}>
                  <option value="above">above</option>
                  <option value="below">below</option>
                  <option value="either">either</option>
                </select>
              </Field>
            </>
          )}

          {intent === 'BREAKOUT_CONFIRM' && (
            <>
              <Field label="Confirmation bars">
                <input type="number" min="1" value={String(params.confirmation_bars ?? '')}
                  onChange={e => updateParam('confirmation_bars', Number(e.target.value))} style={input} />
              </Field>
              <Field label="Side">
                <select value={String(params.side ?? 'above')} onChange={e => updateParam('side', e.target.value)} style={input}>
                  <option value="above">above</option>
                  <option value="below">below</option>
                </select>
              </Field>
            </>
          )}

          {intent === 'OVERTHROW_WATCH' && (
            <>
              <Field label="Max overthrow %">
                <input type="number" step="0.1" value={String(params.max_overthrow_pct ?? '')}
                  onChange={e => updateParam('max_overthrow_pct', Number(e.target.value))} style={input} />
              </Field>
              <Field label="Expected settle bars">
                <input type="number" min="1" value={String(params.expected_settle_bars ?? '')}
                  onChange={e => updateParam('expected_settle_bars', Number(e.target.value))} style={input} />
              </Field>
            </>
          )}

          {intent === 'ABC_PROJECTION' && (
            <Field label="C-leg ratios (comma-separated)">
              <input
                value={Array.isArray(params.ratios) ? (params.ratios as number[]).join(', ') : '1.0, 1.272, 1.618'}
                onChange={e => {
                  const arr = e.target.value.split(',').map(s => Number(s.trim())).filter(n => Number.isFinite(n));
                  updateParam('ratios', arr);
                }}
                placeholder="1.0, 1.272, 1.618"
                style={input}
              />
            </Field>
          )}

          {intent === 'REJECT_ON_TOUCH' && (
            <Field label="Tolerance %">
              <input type="number" step="0.1" value={String(params.tolerance_pct ?? '')}
                onChange={e => updateParam('tolerance_pct', Number(e.target.value))} style={input} />
            </Field>
          )}

          {(intent === 'INVALIDATION' || intent === 'TARGET') && (
            <Field label="Side">
              <select value={String(params.side ?? 'above')} onChange={e => updateParam('side', e.target.value)} style={input}>
                <option value="above">above</option>
                <option value="below">below</option>
              </select>
            </Field>
          )}

          <Field label="Geometry JSON (optional — describe what you drew)">
            <textarea
              value={geometry}
              onChange={e => setGeometry(e.target.value)}
              rows={3}
              placeholder='e.g. {"type":"trendline","points":[{"t":"2026-04-12T00:00:00Z","p":1532.4},{"t":"2026-05-08T00:00:00Z","p":1611.2}]}'
              style={{ ...input, fontFamily: 'monospace', fontSize: 11, resize: 'vertical' }}
            />
          </Field>

          <Field label="Note (the AI reads this)">
            <textarea
              value={note}
              onChange={e => setNote(e.target.value)}
              rows={3}
              maxLength={1000}
              placeholder="e.g. Strong rising support, look for clean retest with reversal candle"
              style={{ ...input, resize: 'vertical' }}
            />
          </Field>

          <Field label={`Weight (importance): ${weight}`}>
            <input type="range" min="1" max="5" value={weight}
              onChange={e => setWeight(Number(e.target.value))}
              style={{ width: '100%' }} />
          </Field>
        </div>

        <div style={footer}>
          <button onClick={onClose} style={btnGray}>Cancel</button>
          <button onClick={handleSave} disabled={saving} style={saving ? btnGray : btnPrimary}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 10 }}>
      <div style={{ fontSize: 11, color: '#666', marginBottom: 3 }}>{label}</div>
      {children}
    </div>
  );
}

const overlay: React.CSSProperties = {
  position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 10000,
};
const modal: React.CSSProperties = {
  background: '#fff', borderRadius: 6, width: 460, maxHeight: '90vh',
  display: 'flex', flexDirection: 'column', boxShadow: '0 8px 24px rgba(0,0,0,0.2)',
};
const header: React.CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
  padding: '10px 14px', borderBottom: '1px solid #e0e0e0',
};
const body: React.CSSProperties = { padding: '12px 14px', overflowY: 'auto' };
const footer: React.CSSProperties = {
  display: 'flex', justifyContent: 'flex-end', gap: 8,
  padding: '10px 14px', borderTop: '1px solid #e0e0e0',
};
const input: React.CSSProperties = {
  width: '100%', padding: '6px 8px', fontSize: 13,
  border: '1px solid #bbb', borderRadius: 4, boxSizing: 'border-box',
  color: '#1f1f1f', background: '#fff',
};
const iconBtn: React.CSSProperties = {
  background: 'transparent', border: 'none', cursor: 'pointer', fontSize: 16, color: '#666',
};
const btnPrimary: React.CSSProperties = {
  background: '#1565c0', color: '#fff', border: 'none', borderRadius: 4,
  padding: '6px 16px', cursor: 'pointer', fontSize: 13, fontWeight: 600,
};
const btnGray: React.CSSProperties = {
  background: '#999', color: '#fff', border: 'none', borderRadius: 4,
  padding: '6px 16px', cursor: 'pointer', fontSize: 13,
};
const errorBox: React.CSSProperties = {
  background: '#fde7e7', border: '1px solid #ef5350', borderRadius: 4,
  padding: '6px 10px', color: '#c62828', fontSize: 12, marginBottom: 8,
};
