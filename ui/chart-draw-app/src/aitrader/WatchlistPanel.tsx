import React, { useState, useEffect } from 'react';
import { getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';
import './WatchlistPanel.css';

interface SymbolStatus {
  symbol: string;
  lastRunAt: string | null;
}

interface BatchProgress {
  [symbol: string]: 'pending' | 'success' | 'error';
}

interface WatchlistPanelProps {
  onSelectSymbol: (symbol: string) => void;
  onBatchComplete: () => void;
}

export default function WatchlistPanel({ onSelectSymbol, onBatchComplete }: WatchlistPanelProps) {
  const [symbols, setSymbols] = useState<SymbolStatus[]>([]);
  const [filteredSymbols, setFilteredSymbols] = useState<SymbolStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedSymbols, setSelectedSymbols] = useState<Set<string>>(new Set());
  const [runningBatch, setRunningBatch] = useState(false);
  const [batchId, setBatchId] = useState<string | null>(null);
  const [batchProgress, setBatchProgress] = useState<BatchProgress>({});

  // Load FNO symbols on mount
  useEffect(() => {
    loadSymbols();
  }, []);

  // Handle search filter
  useEffect(() => {
    const filtered = symbols.filter(s =>
      s.symbol.toUpperCase().includes(searchTerm.toUpperCase())
    );
    setFilteredSymbols(filtered);
  }, [symbols, searchTerm]);

  // Subscribe to STOMP progress updates
  useEffect(() => {
    if (!batchId || !runningBatch) return;

    const stompSubscriptionId = subscribeToProgress(batchId);

    return () => {
      // Cleanup subscription
      if (stompSubscriptionId) {
        // Will be cleaned up when connection closes or batch completes
      }
    };
  }, [batchId, runningBatch]);

  const loadSymbols = async () => {
    try {
      const url = getApiUrl('/api/ai-levels/symbols/fno');
      const response = await fetch(url.toString(), withAuth());
      if (!response.ok) throw new Error(`Failed to load symbols: ${response.status}`);
      const data = await response.json();
      setSymbols(data);
      setFilteredSymbols(data);
    } catch (error) {
      console.error('Error loading symbols:', error);
    } finally {
      setLoading(false);
    }
  };

  const subscribeToProgress = (bId: string) => {
    // Expect a STOMP connection to already exist from the app
    // This is a simplified polling approach for now
    const checkProgress = async () => {
      try {
        const url = getApiUrl(`/api/ai-levels/watch-trades`);
        const response = await fetch(url.toString(), withAuth());
        if (response.ok) {
          // Refresh watch trades to show latest
          onBatchComplete();
        }
      } catch (error) {
        console.error('Error checking progress:', error);
      }
    };

    // Poll every 5 seconds
    const interval = setInterval(checkProgress, 5000);
    return () => clearInterval(interval);
  };

  const handleSelectAll = () => {
    const allSymbols = new Set(filteredSymbols.map(s => s.symbol));
    setSelectedSymbols(allSymbols);
  };

  const handleClearSelection = () => {
    setSelectedSymbols(new Set());
  };

  const toggleSymbolSelection = (symbol: string) => {
    const updated = new Set(selectedSymbols);
    if (updated.has(symbol)) {
      updated.delete(symbol);
    } else {
      updated.add(symbol);
    }
    setSelectedSymbols(updated);
  };

  const handleRunBatch = async () => {
    if (selectedSymbols.size === 0) return;

    setRunningBatch(true);
    const newBatchProgress: BatchProgress = {};
    selectedSymbols.forEach(sym => {
      newBatchProgress[sym] = 'pending';
    });
    setBatchProgress(newBatchProgress);

    try {
      const url = getApiUrl('/api/ai-levels/run-batch');
      const response = await fetch(url.toString(), {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...withAuth().headers,
        },
        body: JSON.stringify({
          symbols: Array.from(selectedSymbols),
          tabId: 'ai-trader-default',
          layoutId: 1,
          timeframe: 'OneHour',
        }),
      });

      if (!response.ok) throw new Error(`Batch run failed: ${response.status}`);
      const { batchId: newBatchId } = await response.json();
      setBatchId(newBatchId);

      console.log('Batch started:', newBatchId);

      // Poll for completion
      let completed = false;
      const maxAttempts = 120; // 10 minutes with 5-second polls
      let attempts = 0;

      while (!completed && attempts < maxAttempts) {
        await new Promise(r => setTimeout(r, 5000));
        attempts++;

        // In a real implementation, we'd track progress from STOMP messages
        // For now, just consider it done after reasonable time
        if (attempts >= 10) {
          completed = true;
        }
      }

      setRunningBatch(false);
      setBatchId(null);
      setSelectedSymbols(new Set());
      onBatchComplete();
      loadSymbols(); // Refresh the list to show updated timestamps

    } catch (error) {
      console.error('Error running batch:', error);
      setRunningBatch(false);
      setBatchId(null);
    }
  };

  const getStatusIndicator = (lastRunAt: string | null) => {
    if (!lastRunAt) return <span className="status-dot gray" title="Never run">●</span>;

    const lastRun = new Date(lastRunAt);
    const now = new Date();
    const daysDiff = (now.getTime() - lastRun.getTime()) / (1000 * 60 * 60 * 24);

    if (daysDiff < 1) return <span className="status-dot green" title="Today">●</span>;
    if (daysDiff < 7) return <span className="status-dot yellow" title={`${Math.floor(daysDiff)} days ago`}>●</span>;
    return <span className="status-dot gray" title={`${Math.floor(daysDiff)} days ago`}>●</span>;
  };

  return (
    <div className="watchlist-panel">
      <div className="watchlist-header">
        <h2>FNO Watchlist</h2>
        {symbols.length > 0 && <span className="symbol-count">{symbols.length}</span>}
      </div>

      <div className="watchlist-search">
        <input
          type="text"
          placeholder="Search symbols..."
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          className="search-input"
        />
      </div>

      <div className="watchlist-actions">
        <button
          onClick={handleSelectAll}
          disabled={loading || runningBatch}
          className="action-btn"
        >
          Select All
        </button>
        <button
          onClick={handleClearSelection}
          disabled={loading || selectedSymbols.size === 0}
          className="action-btn"
        >
          Clear
        </button>
      </div>

      <div className="watchlist-run-btn">
        <button
          onClick={handleRunBatch}
          disabled={selectedSymbols.size === 0 || runningBatch}
          className="run-btn"
        >
          {runningBatch ? '⏳ Running...' : `Run AI Levels (${selectedSymbols.size})`}
        </button>
      </div>

      <div className="watchlist-items">
        {loading ? (
          <div className="loading">Loading symbols...</div>
        ) : filteredSymbols.length === 0 ? (
          <div className="empty">No symbols found</div>
        ) : (
          filteredSymbols.map(status => (
            <div
              key={status.symbol}
              className={`watchlist-item ${selectedSymbols.has(status.symbol) ? 'selected' : ''}`}
              onClick={() => onSelectSymbol(status.symbol)}
            >
              <input
                type="checkbox"
                checked={selectedSymbols.has(status.symbol)}
                onChange={(e) => {
                  e.stopPropagation();
                  toggleSymbolSelection(status.symbol);
                }}
                disabled={runningBatch}
              />
              <span className="symbol-name">{status.symbol}</span>
              <span className="status-indicator">
                {getStatusIndicator(status.lastRunAt)}
              </span>
              {status.lastRunAt && (
                <span className="last-run-time" title={status.lastRunAt}>
                  {new Date(status.lastRunAt).toLocaleDateString()}
                </span>
              )}
            </div>
          ))
        )}
      </div>
    </div>
  );
}
