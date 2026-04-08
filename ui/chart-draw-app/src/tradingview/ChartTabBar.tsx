import React, { useState, useRef, useEffect, useCallback } from 'react';
import { WorkspaceTab } from './workspaceTypes';
import { fetchSymbols, SymbolItem } from './tvApi';

interface ChartTabBarProps {
  tabs: WorkspaceTab[];
  activeTabId: string;
  workspaceName: string;
  onWorkspaceNameChange: (name: string) => void;
  onSwitch: (tabId: string) => void;
  onAdd: () => void;
  onClose: (tabId: string) => void;
  onRename: (tabId: string, newLabel: string) => void;
  onSaveLayout: () => void;
  onSaveAsLayout: () => void;
  onLoadLayout: () => void;
  // Action panel buttons
  isCopilotOpen?: boolean;
  isAnalysisOpen?: boolean;
  isWatchlistOpen?: boolean;
  onToggleCopilot?: () => void;
  onToggleAnalysis?: () => void;
  onToggleWatchlist?: () => void;
}

export default function ChartTabBar({
  tabs, activeTabId, workspaceName, onWorkspaceNameChange,
  onSwitch, onAdd, onClose, onRename, onSaveLayout, onSaveAsLayout, onLoadLayout,
  isCopilotOpen, isAnalysisOpen, isWatchlistOpen, onToggleCopilot, onToggleAnalysis, onToggleWatchlist,
}: ChartTabBarProps) {
  const [editingTabId, setEditingTabId] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');
  const editInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (editingTabId && editInputRef.current) {
      editInputRef.current.focus();
      editInputRef.current.select();
    }
  }, [editingTabId]);

  const startEdit = (tab: WorkspaceTab, e: React.MouseEvent) => {
    e.stopPropagation();
    setEditingTabId(tab.id);
    setEditValue(tab.label);
  };

  const commitEdit = (tabId: string) => {
    const trimmed = editValue.trim();
    if (trimmed) onRename(tabId, trimmed);
    setEditingTabId(null);
  };

  const handleEditKeyDown = (e: React.KeyboardEvent<HTMLInputElement>, tabId: string) => {
    if (e.key === 'Enter') commitEdit(tabId);
    else if (e.key === 'Escape') setEditingTabId(null);
  };

  return (
    <div style={styles.bar}>

      {/* Workspace symbol selector — left side, styled like TV's symbol pill */}
      <WorkspaceSelector name={workspaceName} onChange={onWorkspaceNameChange} />

      {/* Layout buttons — between pill and divider */}
      <SaveButton onSave={onSaveLayout} onSaveAs={onSaveAsLayout} />
      <button style={styles.layoutBtn} onClick={onLoadLayout} title="Load workspace layout">📂</button>

      <div style={styles.divider} />

      {/* Chart tabs */}
      <div style={styles.tabList}>
        {tabs.map(tab => {
          const isActive = tab.id === activeTabId;
          const isEditing = editingTabId === tab.id;

          return (
            <div
              key={tab.id}
              style={{ ...styles.tab, ...(isActive ? styles.tabActive : {}) }}
              onClick={() => !isEditing && onSwitch(tab.id)}
            >
              {isEditing ? (
                <input
                  ref={editInputRef}
                  value={editValue}
                  onChange={e => setEditValue(e.target.value)}
                  onKeyDown={e => handleEditKeyDown(e, tab.id)}
                  onBlur={() => commitEdit(tab.id)}
                  onClick={e => e.stopPropagation()}
                  style={styles.editInput}
                />
              ) : (
                <span
                  style={styles.tabLabel}
                  onDoubleClick={e => startEdit(tab, e)}
                  title="Double-click to rename"
                >
                  {tab.label}
                </span>
              )}
              <span style={styles.tabSub}>{tab.symbol} · {tab.timeframe}</span>
              {tabs.length > 1 && (
                <button
                  style={styles.closeBtn}
                  onClick={e => { e.stopPropagation(); onClose(tab.id); }}
                  title="Close tab"
                >
                  ×
                </button>
              )}
            </div>
          );
        })}
      </div>

      <button style={styles.addBtn} onClick={onAdd} title="New chart tab">+</button>

      {/* Right-side action buttons */}
      {(onToggleCopilot || onToggleAnalysis || onToggleWatchlist) && (
        <>
          <div style={styles.divider} />
          {onToggleCopilot && (
            <button
              style={{ ...styles.actionBtn, background: isCopilotOpen ? '#283593' : undefined, color: isCopilotOpen ? '#fff' : undefined }}
              onClick={onToggleCopilot}
              title="Elliott Analysis"
            >
              📈 Elliott
            </button>
          )}
          {onToggleAnalysis && (
            <button
              style={{ ...styles.actionBtn, background: isAnalysisOpen ? '#1565c0' : undefined, color: isAnalysisOpen ? '#fff' : undefined }}
              onClick={onToggleAnalysis}
              title="Fundamentals, news, snapshots"
            >📊</button>
          )}
          {onToggleWatchlist && (
            <button
              style={{ ...styles.actionBtn, background: isWatchlistOpen ? '#283593' : undefined, color: isWatchlistOpen ? '#fff' : undefined }}
              onClick={onToggleWatchlist}
              title="Toggle Watchlist"
            >
              📋 Watchlist
            </button>
          )}
        </>
      )}
    </div>
  );
}

// ─── Workspace Selector with live symbol search ────────────────────────────

function WorkspaceSelector({ name, onChange }: { name: string; onChange: (v: string) => void }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SymbolItem[]>([]);
  const [highlighted, setHighlighted] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const debounceRef = useRef<number | null>(null);
  const wrapperRef = useRef<HTMLDivElement>(null);

  // Open the dropdown
  const openDropdown = () => {
    setQuery(name);
    setOpen(true);
    setHighlighted(0);
  };

  // Focus input when opened
  useEffect(() => {
    if (open && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [open]);

  // Debounced fetch
  const doFetch = useCallback((q: string) => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(async () => {
      const items = await fetchSymbols(q);
      setResults(items.slice(0, 12));
      setHighlighted(0);
    }, 300);
  }, []);

  useEffect(() => {
    if (open) doFetch(query);
  }, [query, open, doFetch]);

  // Close on outside click
  useEffect(() => {
    if (!open) return;
    const onMouseDown = (e: MouseEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onMouseDown);
    return () => document.removeEventListener('mousedown', onMouseDown);
  }, [open]);

  const select = (symbol: string) => {
    onChange(symbol.toUpperCase());
    setOpen(false);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setHighlighted(h => Math.min(h + 1, results.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setHighlighted(h => Math.max(h - 1, 0)); }
    else if (e.key === 'Enter') {
      e.preventDefault();
      if (results[highlighted]) select(results[highlighted].tradingsymbol);
      else if (query.trim()) select(query.trim());
    }
    else if (e.key === 'Escape') { setOpen(false); }
  };

  return (
    <div ref={wrapperRef} style={styles.workspaceWrapper} title="Workspace symbol — click to search">
      {/* Search icon */}
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="rgba(255,255,255,0.7)" strokeWidth="2.5" style={{ flexShrink: 0 }}>
        <circle cx="11" cy="11" r="7" />
        <line x1="16.5" y1="16.5" x2="22" y2="22" />
      </svg>

      {open ? (
        <input
          ref={inputRef}
          value={query}
          onChange={e => setQuery(e.target.value.toUpperCase())}
          onKeyDown={handleKeyDown}
          style={styles.workspaceInput}
          placeholder="SYMBOL"
          spellCheck={false}
        />
      ) : (
        <span style={styles.workspaceName} onClick={openDropdown}>
          {name || 'WORKSPACE'}
        </span>
      )}

      {/* Dropdown chevron */}
      <svg
        width="10" height="10" viewBox="0 0 24 24" fill="none"
        stroke="rgba(255,255,255,0.5)" strokeWidth="2.5"
        style={{ flexShrink: 0, cursor: 'pointer' }}
        onClick={open ? () => setOpen(false) : openDropdown}
      >
        <polyline points={open ? '6 15 12 9 18 15' : '6 9 12 15 18 9'} />
      </svg>

      {/* Results dropdown */}
      {open && (
        <div style={styles.dropdown}>
          {results.length === 0 && (
            <div style={styles.dropdownEmpty}>Searching…</div>
          )}
          {results.map((item, idx) => {
            const isCurrent = item.tradingsymbol === name;
            const isHl = idx === highlighted;
            return (
              <div
                key={item.tradingsymbol + idx}
                style={{
                  ...styles.dropdownRow,
                  backgroundColor: isHl ? '#e3f2fd' : 'white',
                }}
                onMouseEnter={() => setHighlighted(idx)}
                onMouseDown={e => { e.preventDefault(); select(item.tradingsymbol); }}
              >
                <span style={styles.dropdownSymbol}>{item.tradingsymbol}</span>
                <span style={styles.dropdownMeta}>
                  {[item.exchange, item.instrumentType].filter(Boolean).join(' · ')}
                </span>
                {isCurrent && <span style={styles.dropdownCheck}>✓</span>}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ─── Save split button ────────────────────────────────────────────────────────

function SaveButton({ onSave, onSaveAs }: { onSave: () => void; onSaveAs: () => void }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [saved, setSaved] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [menuOpen]);

  const handleSave = () => {
    onSave();
    setSaved(true);
    setTimeout(() => setSaved(false), 1500);
  };

  return (
    <div ref={ref} style={{ display: 'flex', alignItems: 'stretch', position: 'relative', backgroundColor: '#131722' }}>
      <button
        style={{ ...styles.layoutBtn, minWidth: 36, fontSize: saved ? 11 : 16, color: saved ? '#4caf50' : 'rgba(255,255,255,0.8)', transition: 'color 0.2s' }}
        onClick={handleSave}
        title="Save layout (overwrites current; ▾ for Save As)"
      >
        {saved ? '✓' : '💾'}
      </button>
      <button
        style={{ ...styles.layoutBtn, padding: '0 4px', fontSize: 9, borderLeft: '1px solid rgba(255,255,255,0.15)' }}
        onClick={() => setMenuOpen(o => !o)}
        title="Save As…"
      >▾</button>
      {menuOpen && (
        <div style={{
          position: 'absolute', top: '100%', left: 0,
          backgroundColor: '#1e222d', border: '1px solid rgba(255,255,255,0.12)',
          borderRadius: 6, boxShadow: '0 4px 12px rgba(0,0,0,0.4)',
          zIndex: 10002, minWidth: 130, overflow: 'hidden',
        }}>
          <div style={saveMenuItem} onMouseDown={() => { handleSave(); setMenuOpen(false); }}>💾 Save</div>
          <div style={saveMenuItem} onMouseDown={() => { onSaveAs(); setMenuOpen(false); }}>📋 Save As…</div>
        </div>
      )}
    </div>
  );
}

const saveMenuItem: React.CSSProperties = {
  padding: '8px 14px', fontSize: 12, color: 'rgba(255,255,255,0.85)',
  cursor: 'pointer', whiteSpace: 'nowrap',
};

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles: Record<string, React.CSSProperties> = {
  bar: {
    display: 'flex',
    alignItems: 'stretch',
    backgroundColor: '#f0f0f0',
    borderBottom: '1px solid #d0d0d0',
    height: '38px',
    flexShrink: 0,
    overflow: 'visible',  // needed for dropdown to overflow
    position: 'relative',
    zIndex: 10000,
  },
  divider: {
    width: '1px',
    backgroundColor: '#c0c0c0',
    margin: '6px 0',
    flexShrink: 0,
  },
  tabList: {
    display: 'flex',
    alignItems: 'stretch',
    overflowX: 'auto',
    flex: 1,
    scrollbarWidth: 'none',
  },
  tab: {
    display: 'flex',
    alignItems: 'center',
    gap: '4px',
    padding: '0 10px',
    cursor: 'pointer',
    borderRight: '1px solid #d0d0d0',
    backgroundColor: '#e8e8e8',
    whiteSpace: 'nowrap',
    userSelect: 'none',
    minWidth: '80px',
    transition: 'background-color 0.15s',
  },
  tabActive: {
    backgroundColor: 'white',
    borderBottom: '2px solid #1976d2',
  },
  tabLabel: {
    fontWeight: 600,
    fontSize: '13px',
    color: '#333',
  },
  tabSub: {
    fontSize: '10px',
    color: '#888',
    marginLeft: '2px',
  },
  closeBtn: {
    background: 'none',
    border: 'none',
    cursor: 'pointer',
    fontSize: '16px',
    color: '#888',
    padding: '0 2px',
    lineHeight: 1,
    marginLeft: '4px',
    borderRadius: '3px',
  },
  addBtn: {
    padding: '0 14px',
    background: 'none',
    border: 'none',
    borderLeft: '1px solid #d0d0d0',
    cursor: 'pointer',
    fontSize: '20px',
    color: '#555',
    flexShrink: 0,
  },
  actionBtn: {
    padding: '0 10px',
    background: 'none',
    border: 'none',
    borderLeft: '1px solid #d0d0d0',
    cursor: 'pointer',
    fontSize: '13px',
    fontWeight: 600,
    color: '#333',
    flexShrink: 0,
    whiteSpace: 'nowrap',
    borderRadius: 0,
  },
  layoutBtn: {
    padding: '0 8px',
    background: 'none',
    border: 'none',
    cursor: 'pointer',
    fontSize: '16px',
    color: 'rgba(255,255,255,0.8)',
    backgroundColor: '#131722',
    flexShrink: 0,
    lineHeight: 1,
  },
  editInput: {
    width: '80px',
    fontSize: '13px',
    fontWeight: 600,
    border: '1px solid #1976d2',
    borderRadius: '3px',
    padding: '1px 4px',
    outline: 'none',
  },
  // Workspace selector — TV-style dark pill
  workspaceWrapper: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    padding: '0 12px',
    backgroundColor: '#131722',
    cursor: 'pointer',
    flexShrink: 0,
    minWidth: '100px',
    maxWidth: '200px',
    position: 'relative',
  },
  workspaceName: {
    color: 'white',
    fontSize: '14px',
    fontWeight: 700,
    letterSpacing: '0.3px',
    flex: 1,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    cursor: 'pointer',
  },
  workspaceInput: {
    flex: 1,
    background: 'transparent',
    border: 'none',
    borderBottom: '1px solid rgba(255,255,255,0.5)',
    color: 'white',
    fontSize: '14px',
    fontWeight: 700,
    letterSpacing: '0.3px',
    outline: 'none',
    padding: '2px 0',
    width: '80px',
  },
  dropdown: {
    position: 'absolute',
    top: '100%',
    left: 0,
    minWidth: '240px',
    backgroundColor: 'white',
    border: '1px solid #ddd',
    borderRadius: '6px',
    boxShadow: '0 4px 16px rgba(0,0,0,0.18)',
    zIndex: 10001,
    maxHeight: '260px',
    overflowY: 'auto',
  },
  dropdownEmpty: {
    padding: '12px 14px',
    fontSize: '13px',
    color: '#aaa',
  },
  dropdownRow: {
    display: 'flex',
    alignItems: 'center',
    padding: '8px 14px',
    cursor: 'pointer',
    gap: '8px',
  },
  dropdownSymbol: {
    fontWeight: 700,
    fontSize: '13px',
    color: '#1a1a2e',
    minWidth: '80px',
  },
  dropdownMeta: {
    fontSize: '11px',
    color: '#888',
    flex: 1,
  },
  dropdownCheck: {
    fontSize: '12px',
    color: '#1976d2',
    fontWeight: 700,
  },
};
