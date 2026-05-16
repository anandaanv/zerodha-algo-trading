import { useCallback, useEffect, useState } from 'react';
import AnnotationFormModal from './AnnotationFormModal';
import {
  addJournalNote, deleteAnnotation, deleteJournalNote,
  listAnnotations, listJournalNotes, saveAnnotation,
} from './api';
import {
  AnnotationIntent, DrawingAnnotation, JournalNote,
} from './types';

interface Props {
  symbol: string;
  tabUuid: string;
  interval?: string;
}

function todayYmd(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export default function AnnotationsPanel({ symbol, tabUuid, interval }: Props) {
  const [annotations, setAnnotations] = useState<DrawingAnnotation[]>([]);
  const [journal, setJournal] = useState<JournalNote[]>([]);
  const [loading, setLoading] = useState(false);
  const [showModal, setShowModal] = useState(false);
  const [editTarget, setEditTarget] = useState<DrawingAnnotation | null>(null);

  // New journal note input state
  const [newNoteDate, setNewNoteDate] = useState<string>(todayYmd());
  const [newNoteText, setNewNoteText] = useState<string>('');
  const [savingNote, setSavingNote] = useState(false);
  const [noteError, setNoteError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!symbol || !tabUuid) return;
    setLoading(true);
    try {
      const [ann, notes] = await Promise.all([
        listAnnotations(symbol, tabUuid),
        listJournalNotes(symbol),
      ]);
      setAnnotations(ann);
      setJournal(notes);
    } catch (e: any) {
      console.warn('annotations load failed', e);
    } finally {
      setLoading(false);
    }
  }, [symbol, tabUuid]);

  useEffect(() => { load(); }, [load]);

  async function handleAddJournal() {
    if (!newNoteText.trim()) return;
    setSavingNote(true);
    setNoteError(null);
    try {
      await addJournalNote({
        symbol,
        noteDate: newNoteDate || undefined,
        noteText: newNoteText.trim(),
      });
      setNewNoteText('');
      setNewNoteDate(todayYmd());
      await load();
    } catch (e: any) {
      setNoteError(e?.message || 'Save failed');
    } finally {
      setSavingNote(false);
    }
  }

  async function handleDeleteJournal(id: number) {
    if (!confirm('Delete this journal note?')) return;
    try {
      await deleteJournalNote(id);
      await load();
    } catch (e: any) {
      alert('Delete failed: ' + (e?.message || 'unknown error'));
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

  async function handleDeleteAnnotation(id: number) {
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
      {/* Journal — dated free-form notes */}
      <div style={{ border: '1px solid #e0e0e0', borderRadius: 4, padding: 8, marginBottom: 10 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
          <span style={{ fontSize: 11, color: '#666', textTransform: 'uppercase', letterSpacing: 0.4 }}>
            Trader's journal · {journal.length}
          </span>
        </div>

        {/* Add note input */}
        <div style={{ display: 'flex', gap: 6, marginBottom: 6 }}>
          <input
            type="date"
            value={newNoteDate}
            onChange={e => setNewNoteDate(e.target.value)}
            style={{ ...inputStyle, width: 120, flex: 'none' }}
          />
        </div>
        <textarea
          value={newNoteText}
          onChange={e => setNewNoteText(e.target.value)}
          placeholder="What's on your mind? e.g. 'might start third wave soon', 'earnings tomorrow', 'broke 200dMA'"
          rows={2}
          style={{ ...inputStyle, resize: 'vertical', width: '100%', boxSizing: 'border-box' }}
          onKeyDown={e => {
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleAddJournal();
          }}
        />
        {noteError && <div style={{ fontSize: 11, color: '#c62828', marginTop: 4 }}>{noteError}</div>}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 6 }}>
          <span style={{ fontSize: 10, color: '#999' }}>⌘/Ctrl+Enter to save</span>
          <button
            onClick={handleAddJournal}
            disabled={!newNoteText.trim() || savingNote}
            style={(!newNoteText.trim() || savingNote) ? btnGrayMini : btnPrimaryMini}
          >
            {savingNote ? 'Saving…' : '+ Add note'}
          </button>
        </div>

        {/* Notes list */}
        {journal.length > 0 && (
          <div style={{ marginTop: 8, maxHeight: 280, overflowY: 'auto' }}>
            {journal.map(n => (
              <div key={n.id} style={journalCard}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 6 }}>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 10, color: '#1565c0', fontWeight: 600, marginBottom: 2 }}>
                      {n.noteDate}
                    </div>
                    <div style={{ fontSize: 12, color: '#222', whiteSpace: 'pre-wrap' }}>
                      {n.noteText}
                    </div>
                  </div>
                  <button onClick={() => handleDeleteJournal(n.id)} style={btnLinkDanger}>delete</button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Annotations list header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
        <span style={{ fontSize: 11, color: '#666', textTransform: 'uppercase', letterSpacing: 0.4 }}>
          Drawing annotations · {annotations.length}
        </span>
        <button onClick={() => { setEditTarget(null); setShowModal(true); }} style={btnPrimaryMini}>+ Add</button>
      </div>

      {loading && <div style={{ color: '#999', padding: '8px 0' }}>Loading…</div>}
      {!loading && annotations.length === 0 && (
        <div style={{ color: '#999', fontSize: 12, padding: '8px 0' }}>
          No drawing annotations yet. Click <b>+ Add</b> to attach intent + a note to a drawing.
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
              <button onClick={() => handleDeleteAnnotation(a.id)} style={btnLinkDanger}>delete</button>
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

const inputStyle: React.CSSProperties = {
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
const journalCard: React.CSSProperties = {
  border: '1px solid #eaeaea', borderRadius: 4, padding: '6px 8px',
  marginBottom: 6, background: '#fdfdfd',
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
