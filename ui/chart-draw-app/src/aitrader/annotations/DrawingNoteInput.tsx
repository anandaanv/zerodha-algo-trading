import { useCallback, useEffect, useRef, useState } from 'react';
import { listAnnotations, saveAnnotation } from './api';

interface Props {
  /** TradingView widget (IChartingLibraryWidget). May be null until widget is ready. */
  widget: any | null;
  symbol: string;
  tabUuid: string;
  interval?: string;
  /** Notify parent when a drawing-annotation save completes so it can refresh its list. */
  onSaved?: () => void;
}

/**
 * Inline toolbar input that activates when the trader clicks a drawing on the
 * TradingView chart. Loads the existing free-text note for that drawing (if
 * any), lets the trader edit, and auto-saves to /api/ai-trader/annotations on
 * blur. Hidden when nothing is selected.
 *
 * Uses intent="NOTE" so plain comments don't need a typed intent. The drawing
 * annotation panel renders a NOTE badge for these entries.
 */
export default function DrawingNoteInput({ widget, symbol, tabUuid, interval, onSaved }: Props) {
  const [drawingId, setDrawingId] = useState<string | null>(null);
  const [note, setNote] = useState<string>('');
  const [status, setStatus] = useState<'idle' | 'loading' | 'saving' | 'saved' | 'error'>('idle');
  const [errorMsg, setErrorMsg] = useState<string>('');
  const initialNoteRef = useRef<string>('');
  const inputRef = useRef<HTMLInputElement | null>(null);

  const loadFor = useCallback(async (sourceId: string) => {
    setStatus('loading');
    try {
      const list = await listAnnotations(symbol, tabUuid);
      const existing = list.find(a => a.drawingId === sourceId);
      const existingNote = existing?.note || '';
      setNote(existingNote);
      initialNoteRef.current = existingNote;
      setStatus('idle');
      // Focus the input shortly after render so the trader can type immediately.
      setTimeout(() => { inputRef.current?.focus(); }, 0);
    } catch (e: any) {
      setStatus('error');
      setErrorMsg(e?.message || 'load failed');
    }
  }, [symbol, tabUuid]);

  // Subscribe to widget-level drawing_event so we know when a drawing is clicked.
  useEffect(() => {
    if (!widget || typeof widget.subscribe !== 'function') return;
    const handler = (sourceId: string, event?: string) => {
      // TV's drawing_event fires for many lifecycle phases (create, click, move,
      // remove, properties_changed, points_changed, hide, show). We're after the
      // user-initiated "click" — i.e. selection. Fall back to wide net if event
      // arg is undefined on older versions.
      if (event === undefined || event === 'click' || event === 'create') {
        if (sourceId && sourceId !== drawingId) {
          setDrawingId(sourceId);
          loadFor(sourceId);
        }
      } else if (event === 'remove' && sourceId === drawingId) {
        setDrawingId(null);
        setNote('');
      }
    };
    widget.subscribe('drawing_event', handler);
    return () => {
      try { widget.unsubscribe?.('drawing_event', handler); } catch { /* ignore */ }
    };
  }, [widget, drawingId, loadFor]);

  async function flush() {
    if (!drawingId) return;
    if (note.trim() === initialNoteRef.current.trim()) return;
    setStatus('saving');
    try {
      await saveAnnotation({
        tabUuid, symbol, interval,
        drawingId,
        intent: 'NOTE',
        note,
        weight: 3,
      });
      initialNoteRef.current = note;
      setStatus('saved');
      setTimeout(() => setStatus(s => (s === 'saved' ? 'idle' : s)), 1500);
      onSaved?.();
    } catch (e: any) {
      setStatus('error');
      setErrorMsg(e?.message || 'save failed');
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Escape') {
      // discard edits, restore initial
      setNote(initialNoteRef.current);
      (e.target as HTMLInputElement).blur();
    } else if (e.key === 'Enter') {
      (e.target as HTMLInputElement).blur(); // triggers flush
    }
  }

  if (!drawingId) return null;

  const dirty = note.trim() !== initialNoteRef.current.trim();
  const statusLabel =
    status === 'loading' ? 'loading…'
    : status === 'saving' ? 'saving…'
    : status === 'saved' ? '✓ saved'
    : status === 'error' ? `⚠ ${errorMsg}`
    : dirty ? 'unsaved'
    : '';

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, flex: 1, minWidth: 0 }}>
      <span style={{
        fontSize: 10, color: '#888', fontFamily: 'monospace',
        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 130,
      }} title={drawingId}>📝 {drawingId}</span>
      <input
        ref={inputRef}
        value={note}
        onChange={e => setNote(e.target.value)}
        onBlur={flush}
        onKeyDown={handleKeyDown}
        placeholder="Note for this drawing — saves on blur / Enter"
        style={{
          flex: 1, minWidth: 100,
          fontSize: 12, padding: '4px 8px',
          border: '1px solid #bbb', borderRadius: 4,
          background: '#fff', color: '#1f1f1f',
        }}
      />
      <span style={{
        fontSize: 10, color: status === 'error' ? '#c62828' : status === 'saved' ? '#2e7d32' : '#888',
        whiteSpace: 'nowrap',
      }}>{statusLabel}</span>
      <button
        onClick={() => { setDrawingId(null); setNote(''); }}
        title="Close note input"
        style={{
          background: 'transparent', border: 'none', cursor: 'pointer',
          color: '#999', fontSize: 14, padding: 0, lineHeight: 1,
        }}>✕</button>
    </div>
  );
}
