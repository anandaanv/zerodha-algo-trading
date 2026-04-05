import React, { useState, useEffect } from 'react';
import {
  listWatchlists,
  createWatchlist,
  renameWatchlist,
  deleteWatchlist,
  listWatchlistItems,
  addWatchlistItem,
  removeWatchlistItem,
  importCsv,
  WlWatchlist,
  WlWatchlistItem,
} from './waveLabApi';

interface Props {
  selectedSymbol: string | null;
  selectedWatchlistId: number | null;
  onSymbolSelect: (symbol: string) => void;
  onWatchlistSelect: (id: number) => void;
}

const WaveLabWatchlist: React.FC<Props> = ({
  selectedSymbol,
  selectedWatchlistId,
  onSymbolSelect,
  onWatchlistSelect,
}) => {
  const [watchlists, setWatchlists] = useState<WlWatchlist[]>([]);
  const [items, setItems] = useState<WlWatchlistItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [newWatchlistName, setNewWatchlistName] = useState('');
  const [newSymbol, setNewSymbol] = useState('');
  const [showCsvModal, setShowCsvModal] = useState(false);
  const [csvText, setCsvText] = useState('');
  const [csvLoading, setCsvLoading] = useState(false);
  const [renameId, setRenameId] = useState<number | null>(null);
  const [renameName, setRenameName] = useState('');

  // Load watchlists on mount
  useEffect(() => {
    loadWatchlists();
  }, []);

  // Load items when selected watchlist changes
  useEffect(() => {
    if (selectedWatchlistId !== null) {
      loadItems(selectedWatchlistId);
    } else {
      setItems([]);
    }
  }, [selectedWatchlistId]);

  const loadWatchlists = async () => {
    try {
      setLoading(true);
      const data = await listWatchlists();
      setWatchlists(data);

      // Auto-select first watchlist if none selected
      if (data.length > 0 && selectedWatchlistId === null) {
        onWatchlistSelect(data[0].id);
      }
    } catch (error) {
      console.error('Error loading watchlists:', error);
    } finally {
      setLoading(false);
    }
  };

  const loadItems = async (watchlistId: number) => {
    try {
      const data = await listWatchlistItems(watchlistId);
      setItems(data);
    } catch (error) {
      console.error('Error loading watchlist items:', error);
    }
  };

  const handleCreateWatchlist = async () => {
    const trimmedName = newWatchlistName.trim();
    if (!trimmedName) return;

    try {
      const newWl = await createWatchlist(trimmedName);
      setNewWatchlistName('');
      await loadWatchlists();
      onWatchlistSelect(newWl.id);
    } catch (error) {
      console.error('Error creating watchlist:', error);
    }
  };

  const handleDeleteWatchlist = async (id: number) => {
    if (!window.confirm('Delete this watchlist?')) return;

    try {
      await deleteWatchlist(id);
      await loadWatchlists();
      if (selectedWatchlistId === id) {
        onWatchlistSelect(watchlists.length > 1 ? watchlists[0].id : -1);
      }
    } catch (error) {
      console.error('Error deleting watchlist:', error);
    }
  };

  const handleRenameWatchlist = async (id: number) => {
    const trimmedName = renameName.trim();
    if (!trimmedName) return;

    try {
      await renameWatchlist(id, trimmedName);
      setRenameId(null);
      setRenameName('');
      await loadWatchlists();
    } catch (error) {
      console.error('Error renaming watchlist:', error);
    }
  };

  const handleAddSymbol = async () => {
    const trimmedSymbol = newSymbol.trim().toUpperCase();
    if (!trimmedSymbol || !selectedWatchlistId) return;

    try {
      await addWatchlistItem(selectedWatchlistId, trimmedSymbol);
      setNewSymbol('');
      await loadItems(selectedWatchlistId);
    } catch (error) {
      console.error('Error adding symbol:', error);
    }
  };

  const handleRemoveSymbol = async (symbol: string) => {
    if (!selectedWatchlistId) return;

    try {
      await removeWatchlistItem(selectedWatchlistId, symbol);
      await loadItems(selectedWatchlistId);
    } catch (error) {
      console.error('Error removing symbol:', error);
    }
  };

  const handleImportCsv = async () => {
    if (!csvText.trim() || !selectedWatchlistId) return;

    try {
      setCsvLoading(true);
      await importCsv(selectedWatchlistId, csvText);
      setCsvText('');
      setShowCsvModal(false);
      await loadItems(selectedWatchlistId);
    } catch (error) {
      console.error('Error importing CSV:', error);
      alert('Failed to import CSV');
    } finally {
      setCsvLoading(false);
    }
  };

  const containerStyle: React.CSSProperties = {
    display: 'flex',
    flexDirection: 'column',
    height: '100%',
    width: 220,
    borderRight: '1px solid #ddd',
    background: '#fafafa',
    overflow: 'hidden',
  };

  const headerStyle: React.CSSProperties = {
    padding: '8px',
    fontWeight: 'bold',
    fontSize: '14px',
    borderBottom: '1px solid #ddd',
  };

  const watchlistListStyle: React.CSSProperties = {
    flex: 1,
    overflowY: 'auto',
    minHeight: 0,
  };

  const watchlistRowStyle = (isSelected: boolean): React.CSSProperties => ({
    padding: '8px 12px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    cursor: 'pointer',
    backgroundColor: isSelected ? '#e8f4fd' : 'transparent',
    borderBottom: '1px solid #eee',
    transition: 'background-color 0.2s',
  });

  const buttonStyle: React.CSSProperties = {
    padding: '2px 6px',
    fontSize: '12px',
    border: '1px solid #ccc',
    background: 'white',
    cursor: 'pointer',
    marginLeft: '4px',
  };

  const dividerStyle: React.CSSProperties = {
    height: '1px',
    backgroundColor: '#ddd',
    margin: '0',
  };

  const itemsListStyle: React.CSSProperties = {
    flex: 1,
    overflowY: 'auto',
    minHeight: 0,
  };

  const symbolRowStyle = (isSelected: boolean): React.CSSProperties => ({
    padding: '8px 12px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    cursor: 'pointer',
    backgroundColor: isSelected ? '#e8f4fd' : 'transparent',
    borderBottom: '1px solid #eee',
    transition: 'background-color 0.2s',
    fontSize: '13px',
  });

  const inputStyle: React.CSSProperties = {
    width: '100%',
    padding: '6px 4px',
    fontSize: '12px',
    border: '1px solid #ccc',
    boxSizing: 'border-box',
  };

  const addButtonStyle: React.CSSProperties = {
    width: '100%',
    padding: '6px',
    fontSize: '12px',
    border: '1px solid #ccc',
    background: '#f0f0f0',
    cursor: 'pointer',
    marginTop: '4px',
  };

  const newListButtonStyle: React.CSSProperties = {
    width: 'auto',
    padding: '6px 12px',
    fontSize: '12px',
    border: '1px dashed #999',
    background: 'white',
    cursor: 'pointer',
    margin: '8px',
  };

  const modalOverlayStyle: React.CSSProperties = {
    position: 'fixed',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.3)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 1000,
  };

  const modalCardStyle: React.CSSProperties = {
    backgroundColor: 'white',
    borderRadius: '4px',
    padding: '20px',
    minWidth: '400px',
    boxShadow: '0 4px 6px rgba(0, 0, 0, 0.1)',
  };

  const modalTextareaStyle: React.CSSProperties = {
    width: '100%',
    height: '200px',
    padding: '8px',
    fontSize: '12px',
    border: '1px solid #ccc',
    boxSizing: 'border-box',
    fontFamily: 'monospace',
    marginBottom: '12px',
  };

  const modalButtonsStyle: React.CSSProperties = {
    display: 'flex',
    gap: '8px',
    justifyContent: 'flex-end',
  };

  const modalButtonStyle: React.CSSProperties = {
    padding: '8px 16px',
    fontSize: '12px',
    border: '1px solid #ccc',
    background: '#f0f0f0',
    cursor: 'pointer',
  };

  return (
    <div style={containerStyle}>
      {/* Watchlist Section */}
      <div style={headerStyle}>Wave Lab</div>
      <div style={watchlistListStyle}>
        {watchlists.map((wl) => (
          <div key={wl.id}>
            {renameId === wl.id ? (
              <div style={{ padding: '8px 12px', display: 'flex', gap: '4px' }}>
                <input
                  type="text"
                  value={renameName}
                  onChange={(e) => setRenameName(e.target.value)}
                  autoFocus
                  style={{ flex: 1, fontSize: '12px', padding: '4px' }}
                />
                <button
                  onClick={() => handleRenameWatchlist(wl.id)}
                  style={buttonStyle}
                >
                  Save
                </button>
                <button
                  onClick={() => {
                    setRenameId(null);
                    setRenameName('');
                  }}
                  style={buttonStyle}
                >
                  Cancel
                </button>
              </div>
            ) : (
              <div
                style={watchlistRowStyle(
                  selectedWatchlistId === wl.id
                )}
                onClick={() => onWatchlistSelect(wl.id)}
              >
                <span style={{ flex: 1 }}>{wl.name}</span>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setRenameId(wl.id);
                    setRenameName(wl.name);
                  }}
                  style={buttonStyle}
                  title="Rename"
                >
                  ✏
                </button>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDeleteWatchlist(wl.id);
                  }}
                  style={buttonStyle}
                  title="Delete"
                >
                  🗑
                </button>
              </div>
            )}
          </div>
        ))}
      </div>

      {/* New Watchlist Input */}
      <div style={{ padding: '8px', borderTop: '1px solid #ddd' }}>
        <input
          type="text"
          placeholder="New watchlist..."
          value={newWatchlistName}
          onChange={(e) => setNewWatchlistName(e.target.value)}
          onKeyPress={(e) => e.key === 'Enter' && handleCreateWatchlist()}
          style={inputStyle}
        />
        <button
          onClick={handleCreateWatchlist}
          style={newListButtonStyle}
          disabled={loading}
        >
          + New List
        </button>
      </div>

      {/* Divider */}
      <div style={dividerStyle} />

      {/* Items Section */}
      <div style={headerStyle}>Symbols</div>
      <div style={itemsListStyle}>
        {items.map((item) => (
          <div
            key={item.id}
            style={symbolRowStyle(selectedSymbol === item.symbol)}
            onClick={() => onSymbolSelect(item.symbol)}
          >
            <span style={{ flex: 1 }}>{item.symbol}</span>
            <button
              onClick={(e) => {
                e.stopPropagation();
                handleRemoveSymbol(item.symbol);
              }}
              style={{
                ...buttonStyle,
                marginLeft: '4px',
                background: 'white',
                border: 'none',
                padding: '0',
                fontSize: '14px',
                cursor: 'pointer',
              }}
              title="Remove"
            >
              ×
            </button>
          </div>
        ))}
      </div>

      {/* Add Symbol Input */}
      <div
        style={{
          padding: '8px',
          borderTop: '1px solid #ddd',
          background: '#fff',
        }}
      >
        <input
          type="text"
          placeholder="Add symbol..."
          value={newSymbol}
          onChange={(e) => setNewSymbol(e.target.value)}
          onKeyPress={(e) => e.key === 'Enter' && handleAddSymbol()}
          style={inputStyle}
        />
        <button onClick={handleAddSymbol} style={addButtonStyle}>
          Add
        </button>
        <button
          onClick={() => setShowCsvModal(true)}
          style={{
            ...addButtonStyle,
            marginTop: '4px',
          }}
        >
          Import CSV
        </button>
      </div>

      {/* CSV Modal */}
      {showCsvModal && (
        <div style={modalOverlayStyle}>
          <div style={modalCardStyle}>
            <h3 style={{ margin: '0 0 12px 0' }}>Import Symbols from CSV</h3>
            <textarea
              value={csvText}
              onChange={(e) => setCsvText(e.target.value)}
              placeholder="Enter CSV data (one symbol per line)"
              style={modalTextareaStyle}
            />
            <div style={modalButtonsStyle}>
              <button
                onClick={() => {
                  setShowCsvModal(false);
                  setCsvText('');
                }}
                style={modalButtonStyle}
                disabled={csvLoading}
              >
                Cancel
              </button>
              <button
                onClick={handleImportCsv}
                style={{ ...modalButtonStyle, background: '#1976d2', color: 'white' }}
                disabled={csvLoading}
              >
                {csvLoading ? 'Importing...' : 'Import'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default WaveLabWatchlist;
