import React, { useState, useEffect, useRef } from 'react';
import {
  listWatchlists, createWatchlist, deleteWatchlist,
  addSymbolToWatchlist, removeSymbolFromWatchlist, importCsvToWatchlist,
} from './elliottApi';

interface Watchlist {
  id: number;
  name: string;
  symbols: string[];
}

interface Props {
  open: boolean;
  onClose: () => void;
  onSymbolSelect: (symbol: string) => void;
}

export default function WatchlistSidebar({ open, onClose, onSymbolSelect }: Props) {
  const [watchlists, setWatchlists] = useState<Watchlist[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [newListName, setNewListName] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [newSymbol, setNewSymbol] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) load();
  }, [open]);

  async function load() {
    try {
      const data = await listWatchlists();
      setWatchlists(data);
      if (data.length > 0 && !selectedId) setSelectedId(data[0].id);
    } catch (e: any) {
      setError(e.message);
    }
  }

  const selected = watchlists.find(w => w.id === selectedId);

  async function handleCreate() {
    if (!newListName.trim()) return;
    setLoading(true);
    try {
      await createWatchlist(newListName.trim());
      setNewListName('');
      setShowCreate(false);
      await load();
    } catch (e: any) { setError(e.message); }
    finally { setLoading(false); }
  }

  async function handleDelete() {
    if (!selectedId || !window.confirm(`Delete "${selected?.name}"?`)) return;
    setLoading(true);
    try {
      await deleteWatchlist(selectedId);
      setSelectedId(null);
      await load();
    } catch (e: any) { setError(e.message); }
    finally { setLoading(false); }
  }

  async function handleAddSymbol() {
    if (!selectedId || !newSymbol.trim()) return;
    setLoading(true);
    try {
      await addSymbolToWatchlist(selectedId, newSymbol.trim().toUpperCase());
      setNewSymbol('');
      await load();
    } catch (e: any) { setError(e.message); }
    finally { setLoading(false); }
  }

  async function handleRemoveSymbol(symbol: string) {
    if (!selectedId) return;
    try {
      await removeSymbolFromWatchlist(selectedId, symbol);
      await load();
    } catch (e: any) { setError(e.message); }
  }

  async function handleImportCsv(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file || !selectedId) return;
    setLoading(true);
    try {
      const result = await importCsvToWatchlist(selectedId, file);
      await load();
      alert(`Imported ${result.imported} symbols`);
    } catch (e: any) { setError(e.message); }
    finally {
      setLoading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  }

  return (
    <div style={{
      width: open ? 240 : 0,
      height: '100%',
      overflow: 'hidden',
      transition: 'width 0.2s ease',
      flexShrink: 0,
      borderRight: open ? '1px solid #e0e0e0' : 'none',
      background: '#fafafa',
      display: 'flex',
      flexDirection: 'column',
    }}>
      {open && (
        <>
          {/* Header */}
          <div style={{ background: '#1a237e', color: '#fff', padding: '8px 10px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0 }}>
            <span style={{ fontWeight: 700, fontSize: 13 }}>Watchlists</span>
            <button onClick={onClose} style={miniBtn}>✕</button>
          </div>

          {/* Watchlist selector */}
          <div style={{ padding: '6px 8px', borderBottom: '1px solid #e8e8e8', flexShrink: 0 }}>
            <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
              <select
                value={selectedId ?? ''}
                onChange={e => setSelectedId(Number(e.target.value))}
                style={{ flex: 1, fontSize: 12, padding: '3px 6px', border: '1px solid #ccc', borderRadius: 4 }}
              >
                {watchlists.length === 0 && <option value="">No watchlists</option>}
                {watchlists.map(w => (
                  <option key={w.id} value={w.id}>{w.name}</option>
                ))}
              </select>
              <button onClick={() => setShowCreate(v => !v)} style={iconBtn('#4caf50')} title="New watchlist">+</button>
              <button onClick={handleDelete} disabled={!selectedId} style={iconBtn('#c62828')} title="Delete watchlist">×</button>
            </div>

            {/* Create form */}
            {showCreate && (
              <div style={{ display: 'flex', gap: 4, marginTop: 6 }}>
                <input
                  value={newListName}
                  onChange={e => setNewListName(e.target.value)}
                  onKeyDown={e => e.key === 'Enter' && handleCreate()}
                  placeholder="Watchlist name"
                  style={{ flex: 1, fontSize: 12, padding: '3px 6px', border: '1px solid #ccc', borderRadius: 4 }}
                  autoFocus
                />
                <button onClick={handleCreate} disabled={loading} style={iconBtn('#1976d2')}>✓</button>
              </div>
            )}
          </div>

          {error && (
            <div style={{ margin: '4px 8px', padding: '4px 8px', background: '#ffebee', color: '#c62828', fontSize: 11, borderRadius: 4 }}>
              {error}
              <button onClick={() => setError(null)} style={{ float: 'right', background: 'none', border: 'none', cursor: 'pointer', color: '#c62828' }}>✕</button>
            </div>
          )}

          {/* Symbol list */}
          <div style={{ flex: 1, overflowY: 'auto' }}>
            {!selected && (
              <div style={{ padding: '20px 10px', textAlign: 'center', color: '#bbb', fontSize: 12 }}>
                {watchlists.length === 0 ? 'Create a watchlist to get started.' : 'Select a watchlist.'}
              </div>
            )}
            {selected?.symbols.map(sym => (
              <div
                key={sym}
                style={{
                  display: 'flex', alignItems: 'center', padding: '6px 10px',
                  borderBottom: '1px solid #f0f0f0', cursor: 'pointer',
                  transition: 'background 0.1s',
                }}
                onMouseEnter={e => (e.currentTarget.style.background = '#e8f0fe')}
                onMouseLeave={e => (e.currentTarget.style.background = '')}
              >
                <span
                  onClick={() => onSymbolSelect(sym)}
                  style={{ flex: 1, fontSize: 13, fontWeight: 600, color: '#1a237e' }}
                >
                  {sym}
                </span>
                <button
                  onClick={() => handleRemoveSymbol(sym)}
                  style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#bbb', fontSize: 14, padding: '0 2px' }}
                  title="Remove"
                >×</button>
              </div>
            ))}
          </div>

          {/* Add symbol + import CSV */}
          <div style={{ padding: '6px 8px', borderTop: '1px solid #e8e8e8', flexShrink: 0 }}>
            <div style={{ display: 'flex', gap: 4, marginBottom: 4 }}>
              <input
                value={newSymbol}
                onChange={e => setNewSymbol(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleAddSymbol()}
                placeholder="Add symbol…"
                disabled={!selectedId}
                style={{ flex: 1, fontSize: 12, padding: '3px 6px', border: '1px solid #ccc', borderRadius: 4 }}
              />
              <button onClick={handleAddSymbol} disabled={!selectedId || loading} style={iconBtn('#1976d2')}>+</button>
            </div>
            <div>
              <input
                ref={fileInputRef}
                type="file"
                accept=".csv,.txt"
                onChange={handleImportCsv}
                style={{ display: 'none' }}
                id="watchlist-csv-input"
              />
              <label
                htmlFor="watchlist-csv-input"
                style={{
                  display: 'block', textAlign: 'center', padding: '4px',
                  background: selectedId ? '#e8f0fe' : '#f5f5f5',
                  color: selectedId ? '#1565c0' : '#bbb',
                  borderRadius: 4, fontSize: 11, cursor: selectedId ? 'pointer' : 'default',
                  border: '1px dashed #90caf9',
                }}
              >
                ↑ Import CSV
              </label>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function iconBtn(bg: string): React.CSSProperties {
  return {
    width: 24, height: 24, padding: 0,
    background: bg, color: '#fff', border: 'none',
    borderRadius: 4, cursor: 'pointer', fontSize: 14,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    flexShrink: 0,
  };
}

const miniBtn: React.CSSProperties = {
  padding: '2px 6px', background: 'rgba(255,255,255,0.15)', color: '#fff',
  border: '1px solid rgba(255,255,255,0.3)', borderRadius: 4, cursor: 'pointer', fontSize: 11,
};
