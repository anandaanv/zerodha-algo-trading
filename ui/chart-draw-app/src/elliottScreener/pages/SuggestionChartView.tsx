import React, { useEffect, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { fetchSuggestionChartLayouts, SuggestionChartLayoutDto } from '../api';
import SingleChartPanel from '../../legacy-chart/SingleChartPanel';
import { fetchIntervalMapping } from '../../legacy-chart/proApi';

export default function SuggestionChartView() {
  const { suggestionId } = useParams<{ suggestionId: string }>();
  const [searchParams] = useSearchParams();
  const symbol = searchParams.get('symbol') || '';

  const [layouts, setLayouts] = useState<SuggestionChartLayoutDto[]>([]);
  const [activeTab, setActiveTab] = useState(0);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!suggestionId) {
      setError('No suggestion ID provided');
      setLoading(false);
      return;
    }

    Promise.all([
      fetchSuggestionChartLayouts(Number(suggestionId)),
      fetchIntervalMapping(),
    ])
      .then(([layoutData, mappingData]) => {
        setLayouts(layoutData);
        setMapping(mappingData);
        setLoading(false);
      })
      .catch((e) => {
        setError(e.message);
        setLoading(false);
      });
  }, [suggestionId]);

  if (loading) {
    return (
      <div style={{ padding: 32, color: '#aaa' }}>Loading chart...</div>
    );
  }

  if (error) {
    return (
      <div style={{ padding: 32, color: '#ef9a9a' }}>Error: {error}</div>
    );
  }

  if (!layouts.length) {
    return (
      <div style={{ padding: 32, color: '#aaa' }}>
        No chart layouts available for this suggestion.
      </div>
    );
  }

  const activeLayout = layouts[activeTab];

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100vh',
        background: '#121212',
      }}
    >
      {/* Tab bar */}
      <div
        style={{
          display: 'flex',
          gap: 4,
          padding: '8px 12px',
          background: '#1e1e1e',
          borderBottom: '1px solid #333',
          alignItems: 'center',
        }}
      >
        <span
          style={{
            color: '#aaa',
            marginRight: 12,
            fontWeight: 600,
          }}
        >
          {symbol}
        </span>
        {layouts.map((layout, idx) => (
          <button
            key={layout.timeframe}
            onClick={() => setActiveTab(idx)}
            style={{
              background: idx === activeTab ? '#1976d2' : '#2a2a2a',
              color: '#fff',
              border: 'none',
              padding: '6px 16px',
              borderRadius: 4,
              cursor: 'pointer',
              fontWeight: idx === activeTab ? 700 : 400,
            }}
          >
            {layout.timeframe}
          </button>
        ))}
      </div>

      {/* Chart panel */}
      <div style={{ flex: 1, position: 'relative' }}>
        <SingleChartPanel
          key={activeLayout.timeframe}
          symbol={symbol}
          timeframe={activeLayout.timeframe}
          mapping={mapping}
          showIndicators={false}
          overlaysOverride={activeLayout.overlays}
          readonly={true}
        />
      </div>
    </div>
  );
}
