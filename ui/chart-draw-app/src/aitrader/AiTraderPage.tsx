import React, { useState } from 'react';
import TVChartContainer from '../tradingview/TVChartContainer';
import CollapsibleSection from './CollapsibleSection';
import ActiveTradesPanel from './ActiveTradesPanel';
import WatchTradesPanel from './WatchTradesPanel';
import SimulatedTradesPanel from './SimulatedTradesPanel';
import './AiTraderPage.css';

export default function AiTraderPage() {
  const [selectedSymbol, setSelectedSymbol] = useState<string>('WIPRO');
  const [refreshTick, setRefreshTick] = useState<number>(0);

  const handleSelectSymbol = (symbol: string) => {
    setSelectedSymbol(symbol);
  };

  const handleAnalyseComplete = () => {
    setRefreshTick(prev => prev + 1);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'row', height: '100vh', overflow: 'hidden' }}>
      {/* Left: Watchlist */}
      <div
        style={{
          width: 250,
          borderRight: '1px solid #ddd',
          overflowY: 'auto',
          background: '#fff'
        }}
      >
        {/* Watchlist panel goes here - stub for now */}
        <div style={{ padding: 10, fontSize: 12, color: '#888' }}>Watchlist Panel</div>
      </div>

      {/* Center: Chart */}
      <div
        style={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
          borderRight: '1px solid #ddd',
          background: '#fff'
        }}
      >
        <TVChartContainer symbol={selectedSymbol} timeframe="OneHour" />
      </div>

      {/* Right: 3 stacked panels */}
      <div
        style={{
          width: 320,
          borderLeft: '1px solid #ddd',
          overflowY: 'auto',
          background: '#fff'
        }}
      >
        <CollapsibleSection title="Active trades" defaultOpen={true}>
          <ActiveTradesPanel onSelectSymbol={handleSelectSymbol} refreshTick={refreshTick} />
        </CollapsibleSection>

        <CollapsibleSection title="AI Watch trades" defaultOpen={true}>
          <WatchTradesPanel onSelectSymbol={handleSelectSymbol} refreshTick={refreshTick} />
        </CollapsibleSection>

        <CollapsibleSection title="Simulated trades" defaultOpen={false}>
          <SimulatedTradesPanel onSelectSymbol={handleSelectSymbol} />
        </CollapsibleSection>
      </div>
    </div>
  );
}
