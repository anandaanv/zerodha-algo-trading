import { useCallback, useEffect, useState } from 'react';
import AnnotationFormModal from './AnnotationFormModal';
import {
  deleteAnnotation, getThesis, listAnnotations, saveAnnotation, saveThesis,
} from './api';
import {
  AnnotationIntent, DrawingAnnotation, INTENT_LABELS, SymbolThesis,
} from './types';

interface Props {
  symbol: string;
  tabUuid: string;
  interval?: string;
}

export default function AnnotationsPanel({ symbol, tabUuid, interval }: Props) {
  const [annotations, setAnnotations] = useState<DrawingAnnotation[]>([]);
  const [thesis, setThesis] = useState<SymbolThesis | null>(null);
  const [loading, setLoading] = useState(false);
  const [showModal, setShowModal] = useState(false);
  const [editTarget, setEditTarget] = useState<DrawingAnnotation | null>(null);

  // Editable thesis state
  const [thesisDirty, setThesisDirty] = useState(false);
  const [bias, setBias] = useState<string>('');
  const [regime, setRegime] = useState<string>('');
  const [thesisText, setThesisText] = useState<string>('');
  const [thesisSaving, setThesisSaving] = useState(false);
  const [thesisError, setThesisError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!symbol || !tabUuid) return;
    setLoading(true);
    try {
      const [ann, t] = await Promise.all([
        listAnnotations(symbol, tabUuid),
        getThesis(symbol, tabUuid),
      ]);
      setAnnotations(ann);
      setThesis(t);
      setBias(t?.bias || '');
      setRegime(t?.regime || '');
      setThesisText(t?.thesisText || '');
      setThesisDirty(false);
    } catch (e: any) {
      console.warn('annotations load failed', e);
    } finally {
      setLoading(false);
    }
  }, [symbol, tabUuid]);

  useEffect(() => { load(); }, [load]);

  async function handleSaveThesis() {
    setThesisSaving(true);
    setThesisError(null);
    try {
      const saved = await saveThesis({
        tabUuid, symbol,
        bias: bias || undefined,
        regime: regime || undefined,
        thesisText: thesisText || undefined,
      });
      setThesis(saved);
      setThesisDirty(false);
    } catch (e: any) {
      setThesisError(e?.message || 'Save failed');
    } finally {
      setThesisSaving(false);
    }
  }

  async function handleSaveAnnotation(payload: {
    drawingId?: string;
    intent: AnnotationIntent;
    intentParamsJson?: string;
    geometryJson?: string;
    note?: string;
    weight: number;
  }) {
    await saveAnnotation({
      tabUuid, symbol, interval,
      ...payload,
    });
    await load();
  }

  async function handleDelete(id: number) {
    if (!confirm('Delete this annotation? The AI will no longer see it.')) return;
    try {
      await deleteAnnotation(id);
      await load();
    } catch (e: any) {
      alert('Delete failed: ' + (e?.message || 'unknown error'));
    }
  }

  return (
    <div style={{ padding: '8px 10px', fontSize: 13 }}>
      {/* Thesis editor */}
      <div style={{ border: '1px solid #e0e0e0', borderRadius: 4, padding: 8, marginBottom: 10 }}>
        <div style={{ fontSize: 11, color: '#666', marginBottom: 4 }}>Symbol thesis</div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6, marginBottom: 6 }}>
          <select value={bias} onChange={e => { setBias(e.target.value); setThesisDirty(true); }} style={selectStyle}>
            <option value="">— bias —</option>
            <option value="LONG">LONG</option>
            <option value="SHORT">SHORT</option>
            <option value="NEUTRAL">NEUTRAL</option>
            <option value="RANGE">RANGE</option>
          </select>
          <input value={regime} onChange={e => { setRegime(e.target.value); setThesisDirty(true); }}
            placeholder="regime (e.g. trending)" style={selectStyle} />
        </div>
        <textarea
          value={thesisText}
          onChange={e => { setThesisText(e.target.value); setThesisDirty(true); }}
          placeholder="Your overall view on this symbol (read by AI)"
          rows={3}
          style={{ ...selectStyle, resize: 'vertical', width: '100%', boxSizing: 'border-box' }}
        />
        {thesisError && <div style={{ fontSize: 11, color: '#c62828', marginTop: 4 }}>{thesisError}</div>}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 6 }}>
          <span style={{ fontSize: 10, color: '#999' }}>
            {thesis?.updatedAt ? `Updated ${new Date(thesis.updatedAt).toLocaleString()}` : 'Not yet saved'}
          </span>
          <button
            onClick={handleSaveThesis}
            disabled={!thesisDirty || thesisSaving}
            style={(!thesisDirty || thesisSaving) ? btnGrayMini : btnPrimaryMini}
          >
            {thesisSaving ? 'Saving…' : 'Save thesis'}
          </button>
        </div>
      </div>

      {/* Annotations list header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
        <span style={{ fontSize: 11, color: '#666', textTransform: 'uppercase', letterSpacing: 0.4 }}>
          Annotations · {annotations.length}
        </span>
        <button onClick={() => { setEditTarget(null); setShowModal(true); }} style={btnPrimaryMini}>+ Add</button>
      </div>

      {loading && <div style={{ color: '#999', padding: '8px 0' }}>Loading…</div>}
      {!loading && annotations.length === 0 && (
        <div style={{ color: '#999', fontSize: 12, padding: '8px 0' }}>
          No annotations yet. Click <b>+ Add</b> to attach intent + a note to a drawing.
        </div>
      )}

      {!loading && annotations.map(a => (
        <div key={a.id} style={annCard}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 6 }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
                <span style={intentBadge(a.intent)}>{a.intent.replace(/_/g, ' ')}</span>
                <span style={{ fontSize: 10, color: '#888' }}>★{a.weight}</span>
              </div>
              {a.note && (
                <div style={{ fontSize: 12, color: '#222', whiteSpace: 'pre-wrap' }}>
                  {a.note}
                </div>
              )}
              {a.intentParamsJson && (
                <div style={{ fontFamily: 'monospace', fontSize: 10, color: '#666', marginTop: 2 }}>
                  {a.intentParamsJson}
                </div>
              )}
              {a.drawingId && (
                <div style={{ fontSize: 10, color: '#aaa', marginTop: 2 }}>id: {a.drawingId}</div>
              )}
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <button onClick={() => { setEditTarget(a); setShowModal(true); }} style={btnLink}>edit</button>
              <button onClick={() => handleDelete(a.id)} style={btnLinkDanger}>delete</button>
            </div>
          </div>
        </div>
      ))}

      {showModal && (
        <AnnotationFormModal
          tabUuid={tabUuid}
          symbol={symbol}
          interval={interval}
          initial={editTarget}
          onClose={() => { setShowModal(false); setEditTarget(null); }}
          onSave={handleSaveAnnotation}
        />
      )}
    </div>
  );
}

const selectStyle: React.CSSProperties = {
  fontSize: 12, padding: '4px 6px', border: '1px solid #bbb',
  borderRadius: 3, background: '#fff', color: '#1f1f1f',
};
const btnPrimaryMini: React.CSSProperties = {
  background: '#1565c0', color: '#fff', border: 'none', borderRadius: 3,
  padding: '3px 10px', fontSize: 11, fontWeight: 600, cursor: 'pointer',
};
const btnGrayMini: React.CSSProperties = {
  background: '#bbb', color: '#fff', border: 'none', borderRadius: 3,
  padding: '3px 10px', fontSize: 11, cursor: 'not-allowed',
};
const btnLink: React.CSSProperties = {
  background: 'transparent', color: '#1565c0', border: 'none',
  fontSize: 10, cursor: 'pointer', padding: 0,
};
const btnLinkDanger: React.CSSProperties = { ...btnLink, color: '#c62828' };
const annCard: React.CSSProperties = {
  border: '1px solid #e8e8e8', borderRadius: 4, padding: '6px 8px',
  marginBottom: 6, background: '#fafafa',
};

function intentBadge(intent: string): React.CSSProperties {
  const colors: Record<string, { bg: string; fg: string }> = {
    KEY_LEVEL:        { bg: '#fff3e0', fg: '#e65100' },
    RETEST_ENTRY:     { bg: '#e3f2fd', fg: '#1565c0' },
    BREAKOUT_CONFIRM: { bg: '#e8f5e9', fg: '#2e7d32' },
    REJECT_ON_TOUCH:  { bg: '#fce4ec', fg: '#c2185b' },
    OVERTHROW_WATCH:  { bg: '#f3e5f5', fg: '#7E57C2' },
    ABC_PROJECTION:   { bg: '#ede7f6', fg: '#4527A0' },
    INVALIDATION:     { bg: '#ffebee', fg: '#c62828' },
    TARGET:           { bg: '#e0f7fa', fg: '#00838f' },
  };
  const c = colors[intent] || { bg: '#eee', fg: '#333' };
  return {
    fontSize: 9, padding: '1px 6px', borderRadius: 8,
    background: c.bg, color: c.fg, fontWeight: 600, letterSpacing: 0.3,
  };
}
