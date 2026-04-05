import React, { useState } from 'react';
import WaveLabWatchlist from './WaveLabWatchlist';
import WaveLabChart from './WaveLabChart';
import { runAnalysis, runAnalysisAll, analyzeTriangles, TriangleRunResponse } from './waveLabApi';

const WaveLabApp: React.FC = () => {
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null);
  const [selectedTimeframe, setSelectedTimeframe] = useState<string>('15m');
  const [selectedWatchlistId, setSelectedWatchlistId] = useState<number | null>(
    null
  );
  const [analyzing, setAnalyzing] = React.useState(false);
  const [triangleAnalyzing, setTriangleAnalyzing] = React.useState(false);
  const [triangleResult, setTriangleResult] = React.useState<TriangleRunResponse | null>(null);
  const [chartKey, setChartKey] = React.useState(0);
  const [batchStatus, setBatchStatus] = React.useState<string | null>(null);

  const timeframes = ['1m', '3m', '5m', '15m', '30m', '1h', '4h', '1d'];

  const containerStyle: React.CSSProperties = {
    display: 'flex',
    flexDirection: 'row',
    height: '100vh',
    width: '100%',
    backgroundColor: '#fff',
  };

  const rightPanelStyle: React.CSSProperties = {
    display: 'flex',
    flexDirection: 'column',
    flex: 1,
    overflow: 'hidden',
  };

  const topBarStyle: React.CSSProperties = {
    height: 40,
    display: 'flex',
    flexDirection: 'row',
    alignItems: 'center',
    gap: '8px',
    padding: '0 12px',
    borderBottom: '1px solid #ddd',
    backgroundColor: '#fff',
  };

  const labelStyle: React.CSSProperties = {
    fontSize: '14px',
    fontWeight: 500,
  };

  const timeframeButtonStyle = (isSelected: boolean): React.CSSProperties => ({
    width: 36,
    padding: '6px 0',
    fontSize: '12px',
    border: '1px solid #ccc',
    background: isSelected ? '#1976d2' : '#f0f0f0',
    color: isSelected ? 'white' : '#000',
    cursor: 'pointer',
    borderRadius: '2px',
    transition: 'all 0.2s',
  });

  const dividerStyle: React.CSSProperties = {
    width: '1px',
    height: '20px',
    backgroundColor: '#ddd',
    margin: '0 4px',
  };

  const symbolNameStyle: React.CSSProperties = {
    fontSize: '14px',
    fontWeight: 'bold',
  };

  const chartAreaStyle: React.CSSProperties = {
    flex: 1,
    overflow: 'hidden',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#f5f5f5',
  };

  const placeholderStyle: React.CSSProperties = {
    fontSize: '16px',
    color: '#999',
    textAlign: 'center',
  };

  const handleAnalyze = async () => {
    if (!selectedSymbol) return;
    setAnalyzing(true);
    try {
      await runAnalysis(selectedSymbol, selectedTimeframe);
      setChartKey(k => k + 1); // force chart remount to reload overlays
    } catch (e) {
      console.error('Analysis failed:', e);
    } finally {
      setAnalyzing(false);
    }
  };

  const handleAnalyzeAll = async () => {
    if (!selectedWatchlistId) return;
    setAnalyzing(true);
    setBatchStatus('Running...');
    try {
      const result = await runAnalysisAll(selectedWatchlistId, selectedTimeframe);
      setBatchStatus(`Done: ${result.succeeded}/${result.total} symbols`);
      setChartKey(k => k + 1);
    } catch (e) {
      setBatchStatus('Failed');
      console.error('Batch analysis failed:', e);
    } finally {
      setAnalyzing(false);
    }
  };

  const handleTriangleAnalyze = async () => {
    if (!selectedSymbol) return;
    setTriangleAnalyzing(true);
    try {
      const result = await analyzeTriangles(selectedSymbol, selectedTimeframe, 1000);
      setTriangleResult(result);
    } catch (e) {
      console.error('Triangle analysis failed:', e);
      setTriangleResult(null);
    } finally {
      setTriangleAnalyzing(false);
    }
  };

  return (
    <div style={containerStyle}>
      <WaveLabWatchlist
        selectedSymbol={selectedSymbol}
        selectedWatchlistId={selectedWatchlistId}
        onSymbolSelect={setSelectedSymbol}
        onWatchlistSelect={setSelectedWatchlistId}
      />

      <div style={rightPanelStyle}>
        <div style={topBarStyle}>
          <span style={labelStyle}>Timeframe:</span>
          <div style={{ display: 'flex', gap: '4px' }}>
            {timeframes.map((tf) => (
              <button
                key={tf}
                onClick={() => setSelectedTimeframe(tf)}
                style={timeframeButtonStyle(selectedTimeframe === tf)}
              >
                {tf}
              </button>
            ))}
          </div>

          <button
            onClick={handleAnalyze}
            disabled={analyzing || !selectedSymbol}
            style={{
              backgroundColor: analyzing ? '#888' : '#1976d2',
              color: 'white',
              border: 'none',
              borderRadius: 4,
              padding: '4px 16px',
              cursor: selectedSymbol && !analyzing ? 'pointer' : 'not-allowed',
              marginLeft: 16,
              fontWeight: 600,
            }}
          >
            {analyzing ? 'Analyzing...' : 'Analyze'}
          </button>

          <button
            onClick={handleTriangleAnalyze}
            disabled={triangleAnalyzing || !selectedSymbol}
            style={{
              backgroundColor: triangleAnalyzing ? '#888' : '#6a1b9a',
              color: 'white',
              border: 'none',
              borderRadius: 4,
              padding: '4px 16px',
              cursor: selectedSymbol && !triangleAnalyzing ? 'pointer' : 'not-allowed',
              marginLeft: 4,
              fontWeight: 600,
            }}
          >
            {triangleAnalyzing ? 'Triangles...' : 'Triangle Analyze'}
          </button>

          <button
            onClick={handleAnalyzeAll}
            disabled={analyzing || !selectedWatchlistId}
            style={{
              backgroundColor: analyzing ? '#888' : '#388e3c',
              color: 'white',
              border: 'none',
              borderRadius: 4,
              padding: '4px 16px',
              cursor: selectedWatchlistId && !analyzing ? 'pointer' : 'not-allowed',
              marginLeft: 4,
              fontWeight: 600,
            }}
          >
            Analyze All
          </button>
          {batchStatus && (
            <span style={{ fontSize: 12, color: '#555', marginLeft: 8 }}>{batchStatus}</span>
          )}

          {selectedSymbol && (
            <>
              <div style={dividerStyle} />
              <span style={symbolNameStyle}>{selectedSymbol}</span>
            </>
          )}
        </div>

        {triangleResult && (
          <div
            style={{
              borderBottom: '1px solid #ddd',
              background: '#faf7ff',
              padding: '10px 12px',
              fontSize: 12,
              display: 'grid',
              gap: 6,
            }}
          >
            <div style={{ fontWeight: 700, fontSize: 13 }}>Triangle Final Verdict</div>
            <div>
              Type: <b>{triangleResult.finalTriangleType ?? 'NONE'}</b>
              {' | '}Status: <b>{triangleResult.finalStatus ?? 'NOT_PRESENT'}</b>
              {' | '}Confidence: <b>{triangleResult.finalConfidence?.toFixed(2) ?? '0.00'}</b>
              {' | '}Source: <b>{triangleResult.selectedSource ?? 'SYNTHESIZED'}</b>
            </div>
            <div style={{ color: '#333' }}>{triangleResult.finalReason ?? 'No reasoning available'}</div>
            <details>
              <summary style={{ cursor: 'pointer' }}>Model Outputs (Debug)</summary>
              <div style={{ marginTop: 6, display: 'grid', gap: 6 }}>
                <pre style={{ margin: 0, whiteSpace: 'pre-wrap', maxHeight: 120, overflow: 'auto' }}>
                  A: {triangleResult.proposerAOutputJson ?? ''}
                </pre>
                <pre style={{ margin: 0, whiteSpace: 'pre-wrap', maxHeight: 120, overflow: 'auto' }}>
                  B: {triangleResult.proposerBOutputJson ?? ''}
                </pre>
                <pre style={{ margin: 0, whiteSpace: 'pre-wrap', maxHeight: 120, overflow: 'auto' }}>
                  C: {triangleResult.evaluatorOutputJson ?? ''}
                </pre>
              </div>
            </details>
          </div>
        )}

        <div style={chartAreaStyle}>
          {selectedSymbol ? (
            <WaveLabChart key={chartKey} symbol={selectedSymbol} timeframe={selectedTimeframe} />
          ) : (
            <div style={placeholderStyle}>
              Select a symbol from the watchlist
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default WaveLabApp;
