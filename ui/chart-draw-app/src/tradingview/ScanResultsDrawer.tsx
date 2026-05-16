import React from 'react';

type Drawing = {
  id: string;
  type: string;
  score: number;
  metrics: Record<string, unknown>;
};

type ScanResponse = {
  symbol: string;
  scanTf: string;
  range: { from: number; to: number };
  drawings: Drawing[];
  summary: { totalDrawings: number; bestId: string; bestScore: number };
};

type Props = {
  open: boolean;
  onClose: () => void;
  scanResult: ScanResponse | null;
  loading: boolean;
  error: string | null;
  symbol: string;
  scanTf: string;
};

export default function ScanResultsDrawer({
  open,
  onClose,
  scanResult,
  loading,
  error,
  symbol,
  scanTf,
}: Props) {
  if (!open) return null;

  const sortedDrawings = scanResult
    ? [...scanResult.drawings].sort((a, b) => b.score - a.score)
    : [];

  return (
    <div
      style={{
        width: open ? 360 : 0,
        overflow: 'hidden',
        transition: 'width 0.3s ease',
        flexShrink: 0,
        borderLeft: open ? '1px solid #e0e0e0' : 'none',
        display: 'flex',
        flexDirection: 'column',
        backgroundColor: '#ffffff',
      }}
    >
      <div style={{ padding: '12px', borderBottom: '1px solid #e0e0e0', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div style={{ fontSize: '14px', fontWeight: 600 }}>
          Scan Results · {symbol} · {scanTf}
        </div>
        <button
          onClick={onClose}
          style={{
            background: 'none',
            border: 'none',
            cursor: 'pointer',
            fontSize: '18px',
            padding: '0 4px',
          }}
        >
          ✕
        </button>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: '12px' }}>
        {loading ? (
          <div style={{ textAlign: 'center', padding: '24px 0' }}>
            <div style={{ fontSize: '12px', color: '#999' }}>Scanning...</div>
          </div>
        ) : error ? (
          <div style={{ fontSize: '12px', color: '#d32f2f', padding: '8px' }}>
            Error: {error}
          </div>
        ) : !scanResult ? (
          <div style={{ fontSize: '13px', color: '#999', textAlign: 'center', padding: '24px 0' }}>
            Click Scan to evaluate drawings
          </div>
        ) : (
          <>
            <div style={{ fontSize: '12px', color: '#666', marginBottom: '12px', fontWeight: 500 }}>
              {scanResult.summary.totalDrawings} drawings · best:{' '}
              <strong>
                {sortedDrawings[0]?.type || 'N/A'} @ {scanResult.summary.bestScore.toFixed(2)}
              </strong>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {sortedDrawings.map((drawing) => (
                <div
                  key={drawing.id}
                  style={{
                    padding: '8px',
                    border: '1px solid #e0e0e0',
                    borderRadius: '4px',
                    backgroundColor: '#fafafa',
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '4px' }}>
                    <span
                      style={{
                        fontSize: '11px',
                        fontWeight: 600,
                        padding: '2px 6px',
                        backgroundColor: '#e3f2fd',
                        color: '#1565c0',
                        borderRadius: '3px',
                        textTransform: 'uppercase',
                      }}
                    >
                      {drawing.type}
                    </span>
                    <span style={{ fontSize: '13px', fontWeight: 600, color: '#1565c0' }}>
                      {drawing.score.toFixed(2)}
                    </span>
                  </div>
                  {Object.keys(drawing.metrics).length > 0 && (
                    <div style={{ fontSize: '11px', color: '#666', lineHeight: '1.4' }}>
                      {Object.entries(drawing.metrics).map(([key, value]) => (
                        <div key={key}>
                          {key}: {String(value)}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
