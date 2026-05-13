import React, { useState } from 'react';
import TVChartContainer from '../tradingview/TVChartContainer';
import WatchlistPanel from './WatchlistPanel';
import WatchTradesPanel from './WatchTradesPanel';
import './AiTraderPage.css';

export default function AiTraderPage() {
  const [selectedSymbol, setSelectedSymbol] = useState<string>('WIPRO');
  const [timeframe, setTimeframe] = useState<string>('OneHour');
  const [refreshWatchTrades, setRefreshWatchTrades] = useState<number>(0);

  const handleSelectSymbol = (symbol: string) => {
    setSelectedSymbol(symbol);
  };

  const handleBatchComplete = () => {
    setRefreshWatchTrades(prev => prev + 1);
  };

  return (
    <div className="ai-trader-page">
      <div className="ai-trader-header">
        <h1>AI Trader</h1>
        <div className="header-controls">
          <label>
            Symbol:
            <input
              type="text"
              value={selectedSymbol}
              onChange={(e) => setSelectedSymbol(e.target.value.toUpperCase())}
              placeholder="Enter symbol"
            />
          </label>
          <label>
            Timeframe:
            <select value={timeframe} onChange={(e) => setTimeframe(e.target.value)}>
              <option value="OneMinute">1 min</option>
              <option value="FiveMinute">5 min</option>
              <option value="FifteenMinute">15 min</option>
              <option value="ThirtyMinute">30 min</option>
              <option value="OneHour">1 hour</option>
              <option value="Daily">Daily</option>
            </select>
          </label>
        </div>
      </div>

      <div className="ai-trader-content">
        <div className="watchlist-container">
          <WatchlistPanel
            onSelectSymbol={handleSelectSymbol}
            onBatchComplete={handleBatchComplete}
          />
        </div>

        <div className="chart-container">
          <TVChartContainer
            symbol={selectedSymbol}
            timeframe={timeframe}
          />
        </div>
      </div>

      <div className="watch-trades-container">
        <WatchTradesPanel
          onSelectSymbol={handleSelectSymbol}
          refreshTrigger={refreshWatchTrades}
        />
      </div>
    </div>
  );
}
