import React, { useState, useEffect } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { fetchScanLayouts } from '../../elliottScreener/api';
import SingleChartPanel from '../../legacy-chart/SingleChartPanel';

interface Layout {
  id: number;
  scanId: number;
  timeframe: string;
  tabOrder: number;
  overlays: Record<string, any[]>;
}

export default function ScanChartView() {
  const { scanId } = useParams<{ scanId: string }>();
  const [searchParams] = useSearchParams();
  const symbol = searchParams.get('symbol') || '';

  const [layouts, setLayouts] = useState<Layout[]>([]);
  const [activeTab, setActiveTab] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!scanId) return;
    fetchScanLayouts(Number(scanId))
      .then(data => {
        setLayouts(data);
        setActiveTab(0);
        setLoading(false);
      })
      .catch(e => {
        setError(e.message);
        setLoading(false);
      });
  }, [scanId]);

  if (loading) return <div style={{ color: '#aaa', padding: 40, fontFamily: 'monospace' }}>Loading chart layouts...</div>;
  if (error) return <div style={{ color: '#ef9a9a', padding: 40, fontFamily: 'monospace' }}>Error: {error}</div>;
  if (!layouts.length) return <div style={{ color: '#aaa', padding: 40, fontFamily: 'monospace' }}>No chart layouts available for this scan.</div>;

  const activeLayout = layouts[activeTab];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', background: '#0d1117' }}>
      {/* Header */}
      <div style={{
        background: '#16213e', borderBottom: '1px solid #333', padding: '8px 16px',
        display: 'flex', alignItems: 'center', gap: 16,
      }}>
        <span style={{ color: '#90caf9', fontWeight: 700, fontFamily: 'monospace' }}>{symbol}</span>
        <span style={{ color: '#666', fontSize: 13, fontFamily: 'monospace' }}>Scan #{scanId}</span>
      </div>

      {/* Tabs */}
      <div style={{ display: 'flex', background: '#16213e', borderBottom: '1px solid #333' }}>
        {layouts.map((layout, idx) => (
          <button
            key={layout.timeframe}
            onClick={() => setActiveTab(idx)}
            style={{
              padding: '8px 18px', border: 'none', cursor: 'pointer', fontSize: 13,
              fontFamily: 'monospace', fontWeight: activeTab === idx ? 700 : 400,
              background: activeTab === idx ? '#1a3a5c' : 'transparent',
              color: activeTab === idx ? '#90caf9' : '#888',
              borderBottom: activeTab === idx ? '2px solid #1565c0' : '2px solid transparent',
            }}
          >
            {layout.timeframe}
          </button>
        ))}
      </div>

      {/* Chart */}
      <div style={{ flex: 1, overflow: 'hidden' }}>
        <SingleChartPanel
          key={activeLayout.timeframe}
          symbol={symbol}
          interval={activeLayout.timeframe}
          overlaysOverride={activeLayout.overlays}
          readonly={true}
        />
      </div>
    </div>
  );
}
