import React from 'react';
import { useSearchParams } from 'react-router-dom';
import TVChartContainer from './TVChartContainer';

type Props = {
  symbol: string;
  timeframes: string[];
};

export default function TVMultiPanelChart({ symbol, timeframes }: Props) {
  const [searchParams] = useSearchParams();

  // Calculate grid layout based on number of panels
  const getGridLayout = () => {
    const count = timeframes.length;
    if (count === 1) return { cols: 1, rows: 1 };
    if (count === 2) return { cols: 2, rows: 1 };
    if (count === 3) return { cols: 3, rows: 1 };
    if (count === 4) return { cols: 2, rows: 2 };
    if (count <= 6) return { cols: 3, rows: 2 };
    return { cols: 3, rows: 3 };
  };

  const layout = getGridLayout();

  return (
    <div style={{ width: '100%', height: '100vh', display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <div
        style={{
          padding: '12px 16px',
          background: 'rgba(255,255,255,0.95)',
          borderBottom: '1px solid #e0e0e0',
          display: 'flex',
          alignItems: 'center',
          gap: 12,
        }}
      >
        <span style={{ fontWeight: 600, fontSize: 16, color: '#1976d2' }}>{symbol}</span>
        <span style={{ color: '#666' }}>•</span>
        <span style={{ color: '#666' }}>{timeframes.join(', ')}</span>
      </div>

      {/* Grid of charts */}
      <div
        style={{
          flex: 1,
          display: 'grid',
          gridTemplateColumns: `repeat(${layout.cols}, 1fr)`,
          gridTemplateRows: `repeat(${layout.rows}, 1fr)`,
          gap: 8,
          padding: 8,
          background: '#f5f5f5',
        }}
      >
        {timeframes.map((timeframe) => (
          <div
            key={timeframe}
            style={{
              position: 'relative',
              background: '#fff',
              borderRadius: 8,
              overflow: 'hidden',
              boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
            }}
          >
            {/* Timeframe label */}
            <div
              style={{
                position: 'absolute',
                top: 8,
                left: 8,
                zIndex: 10,
                background: 'rgba(255,255,255,0.9)',
                padding: '4px 8px',
                borderRadius: 4,
                fontSize: 12,
                fontWeight: 600,
                color: '#1976d2',
                boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
              }}
            >
              {timeframe}
            </div>

            {/* TradingView chart container */}
            <TVChartContainer symbol={symbol} timeframe={timeframe} />
          </div>
        ))}
      </div>
    </div>
  );
}
