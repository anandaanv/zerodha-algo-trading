import React, { useState } from 'react';
import {
  WorkspaceLayout,
  WorkspaceLayoutTab,
  loadWorkspaceLayouts,
  saveWorkspaceLayouts,
  getLayoutsForSymbol,
  setLastLayoutIdForSymbol,
} from './workspaceTypes';
import { WorkspaceTab } from './workspaceTypes';

interface WorkspaceLayoutModalProps {
  mode: 'save' | 'load';
  workspaceName: string;
  currentTabs: WorkspaceTab[];
  onSave: (layout: WorkspaceLayout) => void;
  onLoad: (layout: WorkspaceLayout) => void;
  onClose: () => void;
}

export default function WorkspaceLayoutModal({
  mode, workspaceName, currentTabs, onSave, onLoad, onClose,
}: WorkspaceLayoutModalProps) {
  const [name, setName] = useState('');
  const [scope, setScope] = useState<'ALL' | 'symbol'>('ALL');

  const handleSave = () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    const layoutScope = scope === 'ALL' ? 'ALL' : workspaceName;
    const tabs: WorkspaceLayoutTab[] = currentTabs.map(t => ({
      label: t.label,
      symbol: t.symbol,
      timeframe: t.timeframe,
    }));
    const layout: WorkspaceLayout = {
      id: crypto.randomUUID(),
      name: trimmed,
      scope: layoutScope,
      tabs,
      createdAt: Date.now(),
    };
    const existing = loadWorkspaceLayouts();
    saveWorkspaceLayouts([...existing, layout]);
    setLastLayoutIdForSymbol(workspaceName, layout.id);
    onSave(layout);
  };

  const handleDelete = (layoutId: string) => {
    const updated = loadWorkspaceLayouts().filter(l => l.id !== layoutId);
    saveWorkspaceLayouts(updated);
    // Force re-render by triggering a state change via a dummy count
    setDeleteCount(c => c + 1);
  };

  const [deleteCount, setDeleteCount] = useState(0);
  const { symbolSpecific, global } = getLayoutsForSymbol(workspaceName);

  const tabSummary = currentTabs.map(t => `${t.timeframe} · ${t.symbol}`).join(' / ');

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={e => e.stopPropagation()}>

        {mode === 'save' ? (
          <>
            <h3 style={title}>Save Workspace Layout</h3>
            <hr style={divider} />

            <label style={label}>Name</label>
            <input
              autoFocus
              value={name}
              onChange={e => setName(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') handleSave(); if (e.key === 'Escape') onClose(); }}
              placeholder="e.g. Standard Analysis"
              style={input}
            />

            <div style={{ marginTop: 14 }}>
              <label style={{ ...label, display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
                <input type="radio" checked={scope === 'ALL'} onChange={() => setScope('ALL')} />
                All symbols (global template)
              </label>
              <label style={{ ...label, display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', marginTop: 6 }}>
                <input type="radio" checked={scope === 'symbol'} onChange={() => setScope('symbol')} />
                This symbol only ({workspaceName})
              </label>
            </div>

            <p style={{ fontSize: 11, color: '#888', marginTop: 14 }}>
              Tabs: {tabSummary || '(none)'}
            </p>

            <div style={footer}>
              <button style={btnSecondary} onClick={onClose}>Cancel</button>
              <button style={btnPrimary} onClick={handleSave} disabled={!name.trim()}>Save</button>
            </div>
          </>
        ) : (
          <>
            <h3 style={title}>Layouts for {workspaceName}</h3>
            <hr style={divider} />

            {symbolSpecific.length === 0 && global.length === 0 && (
              <p style={{ color: '#888', fontSize: 13, margin: '16px 0' }}>No saved layouts yet.</p>
            )}

            {symbolSpecific.length > 0 && (
              <div style={{ marginBottom: 14 }}>
                <div style={sectionHeader}>{workspaceName} SPECIFIC</div>
                {symbolSpecific.map(layout => (
                  <LayoutRow key={layout.id} layout={layout} onLoad={onLoad} onDelete={handleDelete} />
                ))}
              </div>
            )}

            {global.length > 0 && (
              <div>
                <div style={sectionHeader}>ALL SYMBOLS</div>
                {global.map(layout => (
                  <LayoutRow key={layout.id} layout={layout} onLoad={onLoad} onDelete={handleDelete} />
                ))}
              </div>
            )}

            <div style={footer}>
              <button style={btnSecondary} onClick={onClose}>Cancel</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function LayoutRow({
  layout, onLoad, onDelete,
}: { layout: WorkspaceLayout; onLoad: (l: WorkspaceLayout) => void; onDelete: (id: string) => void }) {
  const preview = layout.tabs.map(t => `${t.timeframe}·${t.symbol}`).join(', ');
  return (
    <div style={rowStyle}>
      <div style={{ flex: 1, overflow: 'hidden' }}>
        <span style={{ fontWeight: 600, fontSize: 13 }}>▶ {layout.name}</span>
        <div style={{ fontSize: 10, color: '#aaa', marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {preview}
        </div>
      </div>
      <button style={btnSmall} onClick={() => onLoad(layout)}>Load</button>
      <button style={{ ...btnSmall, marginLeft: 4, color: '#e57373' }} onClick={() => onDelete(layout.id)}>🗑</button>
    </div>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const overlay: React.CSSProperties = {
  position: 'fixed', inset: 0,
  backgroundColor: 'rgba(0,0,0,0.5)',
  zIndex: 20000,
  display: 'flex', alignItems: 'center', justifyContent: 'center',
};

const modal: React.CSSProperties = {
  backgroundColor: 'white',
  borderRadius: 10,
  padding: '24px 28px',
  minWidth: 380,
  maxWidth: 500,
  maxHeight: '80vh',
  overflowY: 'auto',
  boxShadow: '0 8px 32px rgba(0,0,0,0.25)',
  fontFamily: 'system-ui, sans-serif',
};

const title: React.CSSProperties = {
  margin: '0 0 12px',
  fontSize: 16,
  fontWeight: 700,
  color: '#1a1a2e',
};

const divider: React.CSSProperties = {
  border: 'none', borderTop: '1px solid #e0e0e0', margin: '0 0 16px',
};

const label: React.CSSProperties = {
  display: 'block',
  fontSize: 12,
  fontWeight: 600,
  color: '#555',
  marginBottom: 4,
};

const input: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  border: '1px solid #ccc',
  borderRadius: 6,
  fontSize: 14,
  outline: 'none',
  boxSizing: 'border-box',
};

const footer: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 8,
  marginTop: 20,
};

const btnPrimary: React.CSSProperties = {
  padding: '7px 18px',
  backgroundColor: '#1976d2',
  color: 'white',
  border: 'none',
  borderRadius: 6,
  fontSize: 13,
  fontWeight: 600,
  cursor: 'pointer',
};

const btnSecondary: React.CSSProperties = {
  padding: '7px 18px',
  backgroundColor: 'transparent',
  color: '#555',
  border: '1px solid #ccc',
  borderRadius: 6,
  fontSize: 13,
  cursor: 'pointer',
};

const sectionHeader: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 700,
  color: '#aaa',
  letterSpacing: '0.8px',
  textTransform: 'uppercase',
  marginBottom: 6,
};

const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '8px 4px',
  borderBottom: '1px solid #f0f0f0',
};

const btnSmall: React.CSSProperties = {
  padding: '4px 10px',
  backgroundColor: '#f5f5f5',
  border: '1px solid #ddd',
  borderRadius: 5,
  fontSize: 12,
  cursor: 'pointer',
  whiteSpace: 'nowrap',
};
