import React, { useState, useEffect } from 'react';
import { getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';
import './WatchTradesPanel.css';

interface WatchTrade {
  symbol: string;
  id: string;
  direction: 'LONG' | 'SHORT';
  entry: number;
  stop: number;
  target: number;
  rr: number;
  confidence: number;
  trigger_condition: string;
  rationale: string;
  validity_until: string;
}

interface WatchTradesPanelProps {
  onSelectSymbol: (symbol: string) => void;
  refreshTrigger: number;
}

export default function WatchTradesPanel({ onSelectSymbol, refreshTrigger }: WatchTradesPanelProps) {
  const [watchTrades, setWatchTrades] = useState<WatchTrade[]>([]);
  const [loading, setLoading] = useState(false);

  // Load watch trades on mount and when refresh is triggered
  useEffect(() => {
    loadWatchTrades();
  }, [refreshTrigger]);

  const loadWatchTrades = async () => {
    setLoading(true);
    try {
      const url = getApiUrl('/api/ai-levels/watch-trades');
      const response = await fetch(url.toString(), withAuth());
      if (!response.ok) throw new Error(`Failed to load watch trades: ${response.status}`);
      const data = await response.json();
      setWatchTrades(data);
    } catch (error) {
      console.error('Error loading watch trades:', error);
      setWatchTrades([]);
    } finally {
      setLoading(false);
    }
  };

  const handleRefresh = () => {
    loadWatchTrades();
  };

  const handleRowClick = (symbol: string) => {
    onSelectSymbol(symbol);
  };

  const formatPrice = (price: number | string) => {
    const num = typeof price === 'string' ? parseFloat(price) : price;
    return num.toFixed(2);
  };

  const formatDate = (isoString: string) => {
    try {
      return new Date(isoString).toLocaleDateString();
    } catch {
      return isoString;
    }
  };

  return (
    <div className="watch-trades-panel">
      <div className="watch-trades-header">
        <h3>Active Watch Trades</h3>
        <div className="header-controls">
          <span className="trade-count">{watchTrades.length} trades</span>
          <button onClick={handleRefresh} disabled={loading} className="refresh-btn">
            {loading ? '⟳' : '⟳'} Refresh
          </button>
        </div>
      </div>

      <div className="watch-trades-content">
        {loading ? (
          <div className="loading-message">Loading watch trades...</div>
        ) : watchTrades.length === 0 ? (
          <div className="empty-message">No active watch trades</div>
        ) : (
          <div className="trades-table-wrapper">
            <table className="trades-table">
              <thead>
                <tr>
                  <th>Symbol</th>
                  <th>Dir</th>
                  <th>Entry</th>
                  <th>Stop</th>
                  <th>Target</th>
                  <th>R:R</th>
                  <th>Conf</th>
                  <th>Trigger</th>
                  <th>Until</th>
                </tr>
              </thead>
              <tbody>
                {watchTrades.map((trade) => (
                  <tr
                    key={`${trade.symbol}-${trade.id}`}
                    className={`trade-row ${trade.direction.toLowerCase()}`}
                    onClick={() => handleRowClick(trade.symbol)}
                  >
                    <td className="symbol-cell">
                      <strong>{trade.symbol}</strong>
                    </td>
                    <td className={`direction-cell ${trade.direction.toLowerCase()}`}>
                      {trade.direction === 'LONG' ? '⬆' : '⬇'}
                    </td>
                    <td>{formatPrice(trade.entry)}</td>
                    <td>{formatPrice(trade.stop)}</td>
                    <td>{formatPrice(trade.target)}</td>
                    <td className="rr-cell">{trade.rr.toFixed(2)}x</td>
                    <td className="confidence-cell">
                      <div className="confidence-bar">
                        <div
                          className="confidence-fill"
                          style={{ width: `${trade.confidence * 100}%` }}
                        ></div>
                      </div>
                      <span>{(trade.confidence * 100).toFixed(0)}%</span>
                    </td>
                    <td className="trigger-cell" title={trade.trigger_condition}>
                      {trade.trigger_condition.substring(0, 30)}
                      {trade.trigger_condition.length > 30 ? '...' : ''}
                    </td>
                    <td className="date-cell">{formatDate(trade.validity_until)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
