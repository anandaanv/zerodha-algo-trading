import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';

interface ScanResult {
  symbol?: string;
  primaryTimeframe?: string;
  allTimeframes?: string;
  humanReadable?: string;
  msdSummary?: string;
  aiResponse?: string;
  hasTradeSetup?: boolean;
  aiParsed?: any;
}

interface Scan {
  id: number;
  symbol: string;
  primaryTimeframe: string;
  allTimeframes: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  requestedAt: string;
  completedAt: string | null;
  errorMessage: string | null;
  result: ScanResult | null;
}

interface Props {
  scan: Scan;
}

export default function ScanResultCard({ scan }: Props) {
  const navigate = useNavigate();
  const [showRaw, setShowRaw] = useState(false);
  const [showHuman, setShowHuman] = useState(false);

  const result = scan.result;
  const tradeSetup = result?.aiParsed;

  const statusColor = {
    PENDING: '#ffa726',
    RUNNING: '#42a5f5',
    COMPLETED: result?.hasTradeSetup ? '#66bb6a' : '#aaa',
    FAILED: '#ef5350',
  }[scan.status] ?? '#aaa';

  const statusIcon = {
    PENDING: '⏳',
    RUNNING: '🔄',
    COMPLETED: result?.hasTradeSetup ? '✅' : '✔️',
    FAILED: '❌',
  }[scan.status] ?? '?';

  return (
    <div style={{
      background: '#1a1a2e',
      border: '1px solid #333',
      borderRadius: 8,
      padding: '16px',
      marginBottom: 12,
    }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ fontSize: 18, fontWeight: 700, color: '#e0e0e0' }}>{scan.symbol}</span>
          <span style={{ fontSize: 13, color: '#90caf9' }}>{scan.primaryTimeframe}</span>
          <span style={{ fontSize: 13, color: statusColor, fontWeight: 600 }}>
            {statusIcon} {scan.status}
          </span>
        </div>
        <div style={{ fontSize: 12, color: '#888' }}>
          {new Date(scan.requestedAt).toLocaleString()}
        </div>
      </div>

      {/* Timeframes */}
      {scan.allTimeframes && (
        <div style={{ marginBottom: 8, fontSize: 12, color: '#aaa' }}>
          TFs: {scan.allTimeframes}
        </div>
      )}

      {/* Error */}
      {scan.status === 'FAILED' && scan.errorMessage && (
        <div style={{ background: '#3a1a1a', border: '1px solid #ef5350', borderRadius: 4, padding: 8, fontSize: 13, color: '#ef9a9a', marginBottom: 8 }}>
          {scan.errorMessage}
        </div>
      )}

      {/* Trade Setup */}
      {scan.status === 'COMPLETED' && result?.hasTradeSetup && tradeSetup && (
        <div style={{ background: '#1a2e1a', border: '1px solid #66bb6a', borderRadius: 6, padding: 12, marginBottom: 10 }}>
          <div style={{ color: '#a5d6a7', fontWeight: 700, marginBottom: 8, fontSize: 14 }}>📈 Trade Setup Identified</div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px 20px', fontSize: 13 }}>
            {tradeSetup.hypothesisLabel && <div><span style={{ color: '#888' }}>Hypothesis: </span><span style={{ color: '#e0e0e0' }}>{tradeSetup.hypothesisLabel}</span></div>}
            {tradeSetup.currentStage && <div><span style={{ color: '#888' }}>Stage: </span><span style={{ color: '#ffd54f' }}>{tradeSetup.currentStage}</span></div>}
            {tradeSetup.pattern && <div><span style={{ color: '#888' }}>Pattern: </span><span style={{ color: '#e0e0e0' }}>{tradeSetup.pattern}</span></div>}
            {tradeSetup.waveContext && <div style={{ gridColumn: '1 / -1' }}><span style={{ color: '#888' }}>Wave: </span><span style={{ color: '#80cbc4' }}>{tradeSetup.waveContext}</span></div>}
          </div>
          {tradeSetup.anticipatoryEntry && (
            <div style={{ marginTop: 10, display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '6px 20px', fontSize: 13 }}>
              {tradeSetup.anticipatoryEntry.entryZone && (
                <div style={{ background: '#2a2a1a', borderRadius: 4, padding: '4px 8px' }}>
                  <div style={{ color: '#888', fontSize: 11 }}>Entry Zone</div>
                  <div style={{ color: '#ffcc80', fontWeight: 600 }}>{tradeSetup.anticipatoryEntry.entryZone}</div>
                </div>
              )}
              {tradeSetup.anticipatoryEntry.sl && (
                <div style={{ background: '#2a1a1a', borderRadius: 4, padding: '4px 8px' }}>
                  <div style={{ color: '#888', fontSize: 11 }}>Stop Loss</div>
                  <div style={{ color: '#ef9a9a', fontWeight: 600 }}>{tradeSetup.anticipatoryEntry.sl}</div>
                </div>
              )}
              {tradeSetup.anticipatoryEntry.tp && (
                <div style={{ background: '#1a2a1a', borderRadius: 4, padding: '4px 8px' }}>
                  <div style={{ color: '#888', fontSize: 11 }}>Target</div>
                  <div style={{ color: '#a5d6a7', fontWeight: 600 }}>{tradeSetup.anticipatoryEntry.tp}</div>
                </div>
              )}
            </div>
          )}
          {tradeSetup.anticipatoryEntry?.triggerDescription && (
            <div style={{ marginTop: 8, fontSize: 12, color: '#b0bec5' }}>
              <span style={{ color: '#888' }}>Trigger: </span>{tradeSetup.anticipatoryEntry.triggerDescription}
            </div>
          )}
        </div>
      )}

      {/* No trade setup */}
      {scan.status === 'COMPLETED' && !result?.hasTradeSetup && (
        <div style={{ color: '#888', fontSize: 13, marginBottom: 8 }}>
          No actionable trade setup identified at this time.
        </div>
      )}

      {/* Actions */}
      {scan.status === 'COMPLETED' && (
        <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
          <button
            onClick={() => navigate(`/scan-chart/${scan.id}?symbol=${scan.symbol}`)}
            style={{
              background: '#1a3a5c', color: '#90caf9', border: '1px solid #1565c0',
              borderRadius: 4, padding: '4px 12px', cursor: 'pointer', fontSize: 13,
            }}
          >
            View Chart ↗
          </button>
          <button
            onClick={() => setShowHuman(!showHuman)}
            style={{
              background: '#1a2a1a', color: '#a5d6a7', border: '1px solid #2e7d32',
              borderRadius: 4, padding: '4px 12px', cursor: 'pointer', fontSize: 13,
            }}
          >
            {showHuman ? 'Hide Analysis' : 'Show Analysis'}
          </button>
          <button
            onClick={() => setShowRaw(!showRaw)}
            style={{
              background: '#1a1a2e', color: '#888', border: '1px solid #444',
              borderRadius: 4, padding: '4px 12px', cursor: 'pointer', fontSize: 13,
            }}
          >
            {showRaw ? 'Hide AI' : 'Raw AI'}
          </button>
        </div>
      )}

      {/* Human readable */}
      {showHuman && result?.humanReadable && (
        <pre style={{
          marginTop: 10, background: '#0d1117', border: '1px solid #333',
          borderRadius: 4, padding: 10, fontSize: 11, color: '#c9d1d9',
          whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 300, overflow: 'auto',
        }}>
          {result.humanReadable}
        </pre>
      )}

      {/* Raw AI response */}
      {showRaw && result?.aiResponse && (
        <pre style={{
          marginTop: 10, background: '#0d1117', border: '1px solid #333',
          borderRadius: 4, padding: 10, fontSize: 11, color: '#8b949e',
          whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 300, overflow: 'auto',
        }}>
          {result.aiResponse}
        </pre>
      )}
    </div>
  );
}
