import React, { useEffect, useRef, useState } from 'react';
import { fetchIndices, IndexInfo } from '../api';

interface Props {
  currentSymbols: string; // comma-separated current value
  onAdd: (token: string) => void; // called with e.g. "INDEX-NIFTY50"
}

export default function IndexSymbolPicker({ currentSymbols, onAdd }: Props) {
  const [open, setOpen] = useState(false);
  const [indices, setIndices] = useState<IndexInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    fetchIndices().then(setIndices).finally(() => setLoading(false));
  }, [open]);

  // Close on outside click
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  const currentTokens = currentSymbols.split(',').map(s => s.trim().toUpperCase());

  return (
    <div ref={ref} style={{ position: 'relative', display: 'inline-block' }}>
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        style={{
          background: '#2e4a7a',
          color: '#90caf9',
          border: '1px solid #42a5f5',
          borderRadius: 4,
          padding: '4px 10px',
          fontSize: 12,
          cursor: 'pointer',
          whiteSpace: 'nowrap',
        }}
      >
        + Add Index
      </button>

      {open && (
        <div
          style={{
            position: 'absolute',
            top: '100%',
            left: 0,
            zIndex: 100,
            background: '#1e1e1e',
            border: '1px solid #444',
            borderRadius: 6,
            minWidth: 220,
            marginTop: 4,
            boxShadow: '0 4px 16px rgba(0,0,0,0.5)',
          }}
        >
          {loading && (
            <div style={{ padding: '10px 14px', color: '#aaa', fontSize: 13 }}>
              Loading...
            </div>
          )}
          {!loading && indices.length === 0 && (
            <div style={{ padding: '10px 14px', color: '#aaa', fontSize: 13 }}>
              No indices available
            </div>
          )}
          {indices.map(idx => {
            const token = `INDEX-${idx.name}`;
            const alreadyAdded = currentTokens.includes(token.toUpperCase());
            return (
              <div
                key={idx.name}
                onClick={() => {
                  if (!alreadyAdded) {
                    onAdd(token);
                    setOpen(false);
                  }
                }}
                style={{
                  padding: '8px 14px',
                  cursor: alreadyAdded ? 'default' : 'pointer',
                  opacity: alreadyAdded ? 0.45 : 1,
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  fontSize: 13,
                  color: '#fff',
                  borderBottom: '1px solid #2a2a2a',
                }}
                onMouseEnter={e => {
                  if (!alreadyAdded) (e.currentTarget as HTMLDivElement).style.background = '#2a3a5a';
                }}
                onMouseLeave={e => {
                  (e.currentTarget as HTMLDivElement).style.background = 'transparent';
                }}
              >
                <span>{idx.displayName}</span>
                <span style={{ color: '#888', fontSize: 11 }}>
                  {alreadyAdded ? 'added' : `${idx.symbolCount} symbols`}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
