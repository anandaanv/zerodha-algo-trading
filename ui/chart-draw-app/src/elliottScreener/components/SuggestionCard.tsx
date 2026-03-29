import React, { useState, useCallback } from 'react';
import { ElliottTradeSuggestion } from '../types';
import { acceptSuggestion, activateSuggestion, closeSuggestion, rejectSuggestion } from '../api';

interface Props {
  suggestion: ElliottTradeSuggestion;
  onUpdated: (s: ElliottTradeSuggestion) => void;
}

function stateColor(state: string): string {
  switch (state) {
    case 'PROPOSED': return '#90caf9';
    case 'ANTICIPATORY': return '#ffcc80';
    case 'ACTIVE': return '#a5d6a7';
    case 'SUCCESSFUL': return '#69f0ae';
    case 'FAILED': return '#ef9a9a';
    case 'REJECTED': return '#757575';
    default: return '#666';
  }
}

function fmtDate(iso: string | null): string {
  if (!iso) return '';
  try {
    return new Date(iso).toLocaleDateString();
  } catch {
    return iso;
  }
}

export default function SuggestionCard({ suggestion, onUpdated }: Props) {
  const [expanded, setExpanded] = useState(false);
  const [notes, setNotes] = useState('');
  const [acting, setActing] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const handleAction = useCallback(
    async (action: string) => {
      setActing(true);
      setActionError(null);
      try {
        let updated: ElliottTradeSuggestion;
        switch (action) {
          case 'accept':
            updated = await acceptSuggestion(suggestion.id, notes || undefined);
            break;
          case 'activate':
            updated = await activateSuggestion(suggestion.id, notes || undefined);
            break;
          case 'close-success':
            updated = await closeSuggestion(suggestion.id, true, notes || undefined);
            break;
          case 'close-fail':
            updated = await closeSuggestion(suggestion.id, false, notes || undefined);
            break;
          case 'reject':
            updated = await rejectSuggestion(suggestion.id, notes || undefined);
            break;
          default:
            return;
        }
        setNotes('');
        onUpdated(updated);
      } catch (e: any) {
        setActionError(e.message);
      } finally {
        setActing(false);
      }
    },
    [suggestion.id, notes, onUpdated]
  );

  return (
    <div
      style={{
        background: '#242424',
        border: '1px solid #333',
        borderRadius: 8,
        marginBottom: 12,
        overflow: 'hidden',
      }}
    >
      <div
        onClick={() => setExpanded(!expanded)}
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          padding: '12px 16px',
          cursor: 'pointer',
          alignItems: 'center',
        }}
      >
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <span style={{ fontWeight: 700, fontSize: 18 }}>{suggestion.symbol}</span>
          <span
            style={{
              color: suggestion.direction === 'LONG' ? '#a5d6a7' : '#ef9a9a',
              fontWeight: 600,
            }}
          >
            {suggestion.direction}
          </span>
          <span
            style={{
              background: stateColor(suggestion.state),
              color: '#000',
              padding: '2px 8px',
              borderRadius: 4,
              fontSize: 12,
            }}
          >
            {suggestion.state}
          </span>
        </div>
        <div style={{ color: '#aaa', fontSize: 13 }}>
          {suggestion.entryZone && `Entry: ${suggestion.entryZone}`}
          <span style={{ marginLeft: 16 }}>{fmtDate(suggestion.createdAt)}</span>
          <span style={{ marginLeft: 8 }}>{expanded ? '▲' : '▼'}</span>
        </div>
      </div>

      {expanded && (
        <div style={{ padding: '0 16px 16px' }}>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(3, 1fr)',
              gap: 8,
              marginBottom: 12,
            }}
          >
            <div>
              <span style={{ color: '#aaa' }}>Entry Zone</span>
              <br />
              {suggestion.entryZone ?? '—'}
            </div>
            <div>
              <span style={{ color: '#aaa' }}>Stop Loss</span>
              <br />
              <span style={{ color: '#ef9a9a' }}>{suggestion.stopLoss ?? '—'}</span>
            </div>
            <div>
              <span style={{ color: '#aaa' }}>Target</span>
              <br />
              <span style={{ color: '#a5d6a7' }}>{suggestion.target1 ?? '—'}</span>
            </div>
          </div>

          {suggestion.hypothesisLabel && (
            <div style={{ marginBottom: 8 }}>
              <span style={{ color: '#90caf9' }}>Hypothesis: </span>
              {suggestion.hypothesisLabel}
            </div>
          )}
          {suggestion.waveContext && (
            <div style={{ marginBottom: 8, color: '#ccc' }}>
              <span style={{ color: '#90caf9' }}>Wave Context: </span>
              {suggestion.waveContext}
            </div>
          )}
          {suggestion.pattern && (
            <div style={{ marginBottom: 8 }}>
              <span style={{ color: '#90caf9' }}>Pattern: </span>
              {suggestion.pattern}
            </div>
          )}

          {suggestion.triggerDescription && (
            <div style={{ marginBottom: 8, background: '#1a1a1a', padding: 8, borderRadius: 4 }}>
              <span style={{ color: '#ffcc80' }}>Trigger: </span>
              {suggestion.triggerDescription}
            </div>
          )}

          {suggestion.confidenceLayers && Object.keys(suggestion.confidenceLayers).length > 0 && (
            <div style={{ marginBottom: 8 }}>
              <div style={{ color: '#90caf9', marginBottom: 4 }}>Confidence Layers</div>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {Object.entries(suggestion.confidenceLayers).map(([layer, verdict]) => (
                  <span
                    key={layer}
                    style={{
                      background:
                        verdict === 'pass'
                          ? '#1b5e20'
                          : verdict === 'fail'
                            ? '#b71c1c'
                            : verdict === 'warning'
                              ? '#e65100'
                              : '#333',
                      padding: '2px 8px',
                      borderRadius: 4,
                      fontSize: 12,
                    }}
                  >
                    {layer}: {verdict}
                  </span>
                ))}
              </div>
            </div>
          )}

          {suggestion.invalidationConditions && suggestion.invalidationConditions.length > 0 && (
            <details style={{ marginBottom: 8 }}>
              <summary
                style={{
                  color: '#ef9a9a',
                  cursor: 'pointer',
                }}
              >
                Invalidation Conditions ({suggestion.invalidationConditions.length})
              </summary>
              <ul style={{ color: '#ccc', marginTop: 4 }}>
                {suggestion.invalidationConditions.map((c, i) => (
                  <li key={i}>{c}</li>
                ))}
              </ul>
            </details>
          )}

          {suggestion.reasoning && (
            <details style={{ marginBottom: 8 }}>
              <summary style={{ color: '#aaa', cursor: 'pointer' }}>AI Reasoning</summary>
              <p style={{ color: '#ccc', marginTop: 4 }}>{suggestion.reasoning}</p>
            </details>
          )}

          {suggestion.userNotes && (
            <div style={{ marginBottom: 8, color: '#ffcc80' }}>Notes: {suggestion.userNotes}</div>
          )}

          {suggestion.symbol && (
            <div style={{ marginBottom: 8 }}>
              <a
                href={`/suggestion-chart/${suggestion.id}?symbol=${encodeURIComponent(suggestion.symbol)}`}
                target="_blank"
                rel="noopener noreferrer"
                style={{
                  display: 'inline-block',
                  background: '#1565c0',
                  color: '#fff',
                  padding: '6px 16px',
                  borderRadius: 4,
                  textDecoration: 'none',
                  fontSize: 13,
                }}
              >
                View Chart
              </a>
            </div>
          )}

          {['PROPOSED', 'ANTICIPATORY', 'ACTIVE'].includes(suggestion.state) && (
            <textarea
              placeholder="Add notes (optional)..."
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={2}
              style={{
                width: '100%',
                background: '#1a1a1a',
                color: '#fff',
                border: '1px solid #444',
                borderRadius: 4,
                padding: 8,
                marginBottom: 8,
                boxSizing: 'border-box',
              }}
            />
          )}

          <div style={{ display: 'flex', gap: 8 }}>
            {suggestion.state === 'PROPOSED' && (
              <>
                <button
                  onClick={() => handleAction('accept')}
                  disabled={acting}
                  style={{
                    background: '#1565c0',
                    color: '#fff',
                    border: 'none',
                    padding: '6px 16px',
                    borderRadius: 4,
                    cursor: 'pointer',
                  }}
                >
                  Accept (Watch)
                </button>
                <button
                  onClick={() => handleAction('reject')}
                  disabled={acting}
                  style={{
                    background: '#424242',
                    color: '#aaa',
                    border: 'none',
                    padding: '6px 16px',
                    borderRadius: 4,
                    cursor: 'pointer',
                  }}
                >
                  Reject
                </button>
              </>
            )}

            {suggestion.state === 'ANTICIPATORY' && (
              <>
                <button
                  onClick={() => handleAction('activate')}
                  disabled={acting}
                  style={{
                    background: '#2e7d32',
                    color: '#fff',
                    border: 'none',
                    padding: '6px 16px',
                    borderRadius: 4,
                    cursor: 'pointer',
                  }}
                >
                  Activate Trade
                </button>
                <button
                  onClick={() => handleAction('reject')}
                  disabled={acting}
                  style={{
                    background: '#424242',
                    color: '#aaa',
                    border: 'none',
                    padding: '6px 16px',
                    borderRadius: 4,
                    cursor: 'pointer',
                  }}
                >
                  Cancel
                </button>
              </>
            )}

            {suggestion.state === 'ACTIVE' && (
              <>
                <button
                  onClick={() => handleAction('close-success')}
                  disabled={acting}
                  style={{
                    background: '#2e7d32',
                    color: '#fff',
                    border: 'none',
                    padding: '6px 16px',
                    borderRadius: 4,
                    cursor: 'pointer',
                  }}
                >
                  Close — Successful
                </button>
                <button
                  onClick={() => handleAction('close-fail')}
                  disabled={acting}
                  style={{
                    background: '#c62828',
                    color: '#fff',
                    border: 'none',
                    padding: '6px 16px',
                    borderRadius: 4,
                    cursor: 'pointer',
                  }}
                >
                  Close — Failed
                </button>
              </>
            )}
          </div>

          {actionError && (
            <p style={{ color: '#ef9a9a', marginTop: 8 }}>{actionError}</p>
          )}
        </div>
      )}
    </div>
  );
}
