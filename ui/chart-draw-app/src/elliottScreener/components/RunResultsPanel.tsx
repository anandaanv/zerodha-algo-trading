import React, { useEffect, useState } from 'react';
import { ElliottScreenerRun, ElliottScreenerRunResult, RunResultStatus } from '../types';
import { fetchRunResults, getRunHistory } from '../api';

interface Props {
  screenerId: number;
}

const STATUS_COLORS: Record<RunResultStatus, string> = {
  PASSED: '#2e7d32',
  SKIPPED: '#555',
  FAILED: '#e65100',
  ERROR: '#b71c1c',
};

const STATUS_LABELS: Record<RunResultStatus, string> = {
  PASSED: 'Passed',
  SKIPPED: 'Skipped',
  FAILED: 'No Setup',
  ERROR: 'Error',
};

function StatusBadge({ status }: { status: RunResultStatus }) {
  return (
    <span style={{
      background: STATUS_COLORS[status],
      color: '#fff',
      borderRadius: 3,
      padding: '2px 7px',
      fontSize: 11,
      fontWeight: 600,
      letterSpacing: 0.3,
    }}>
      {STATUS_LABELS[status]}
    </span>
  );
}

export default function RunResultsPanel({ screenerId }: Props) {
  const [runs, setRuns] = useState<ElliottScreenerRun[]>([]);
  const [expandedRunId, setExpandedRunId] = useState<number | null>(null);
  const [results, setResults] = useState<Record<number, ElliottScreenerRunResult[]>>({});
  const [loadingResults, setLoadingResults] = useState<Record<number, boolean>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    getRunHistory(screenerId)
      .then(setRuns)
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [screenerId]);

  const toggleRun = async (runId: number) => {
    if (expandedRunId === runId) {
      setExpandedRunId(null);
      return;
    }
    setExpandedRunId(runId);
    if (!results[runId]) {
      setLoadingResults(prev => ({ ...prev, [runId]: true }));
      try {
        const data = await fetchRunResults(screenerId, runId);
        setResults(prev => ({ ...prev, [runId]: data }));
      } catch (e: any) {
        setResults(prev => ({ ...prev, [runId]: [] }));
      } finally {
        setLoadingResults(prev => ({ ...prev, [runId]: false }));
      }
    }
  };

  const fmtDate = (iso: string | null) => {
    if (!iso) return '—';
    try { return new Date(iso).toLocaleString(); } catch { return iso; }
  };

  const fmtMs = (ms: number | null) => {
    if (ms == null) return '—';
    return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
  };

  if (loading) return <p style={{ color: '#aaa', fontSize: 13 }}>Loading run history...</p>;
  if (error) return <p style={{ color: '#ef9a9a', fontSize: 13 }}>{error}</p>;
  if (runs.length === 0) return <p style={{ color: '#555', fontSize: 13 }}>No runs yet.</p>;

  return (
    <div style={{ marginTop: 8 }}>
      {runs.map(run => {
        const isExpanded = expandedRunId === run.id;
        const runResults = results[run.id] || [];
        const isLoadingResults = loadingResults[run.id];

        return (
          <div key={run.id} style={{ marginBottom: 8, border: '1px solid #2a2a2a', borderRadius: 6, overflow: 'hidden' }}>
            {/* Run header */}
            <div
              onClick={() => toggleRun(run.id)}
              style={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                padding: '8px 14px', cursor: 'pointer', background: '#1e1e1e',
              }}
            >
              <div style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
                <span style={{
                  fontSize: 11, fontWeight: 700, padding: '2px 7px', borderRadius: 3,
                  background: run.status === 'COMPLETED' ? '#1b5e20' : run.status === 'RUNNING' ? '#1565c0' : '#333',
                  color: '#fff',
                }}>
                  {run.status}
                </span>
                <span style={{ color: '#aaa', fontSize: 12 }}>
                  {fmtDate(run.startedAt)}
                </span>
                <span style={{ color: '#666', fontSize: 12 }}>
                  {run.processedSymbols}/{run.totalSymbols} symbols ·{' '}
                  <span style={{ color: '#4caf50' }}>{run.suggestionsCreated} passed</span>
                  {run.duplicatesSkipped > 0 && ` · ${run.duplicatesSkipped} skipped`}
                </span>
              </div>
              <span style={{ color: '#555', fontSize: 12 }}>{isExpanded ? '▲' : '▼'}</span>
            </div>

            {/* Per-symbol results */}
            {isExpanded && (
              <div style={{ background: '#161616', padding: '8px 0' }}>
                {isLoadingResults && (
                  <p style={{ color: '#aaa', fontSize: 12, padding: '6px 14px', margin: 0 }}>Loading results...</p>
                )}
                {!isLoadingResults && runResults.length === 0 && (
                  <p style={{ color: '#555', fontSize: 12, padding: '6px 14px', margin: 0 }}>No per-symbol results recorded for this run.</p>
                )}
                {!isLoadingResults && runResults.length > 0 && (
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                    <thead>
                      <tr style={{ color: '#666' }}>
                        <th style={{ padding: '4px 14px', textAlign: 'left', fontWeight: 600 }}>Symbol</th>
                        <th style={{ padding: '4px 8px', textAlign: 'left', fontWeight: 600 }}>Status</th>
                        <th style={{ padding: '4px 8px', textAlign: 'left', fontWeight: 600 }}>Time</th>
                        <th style={{ padding: '4px 8px', textAlign: 'left', fontWeight: 600 }}>Details</th>
                      </tr>
                    </thead>
                    <tbody>
                      {runResults.map(r => (
                        <tr key={r.id} style={{ borderTop: '1px solid #1e1e1e' }}>
                          <td style={{ padding: '5px 14px', color: '#fff', fontWeight: 500 }}>{r.symbol}</td>
                          <td style={{ padding: '5px 8px' }}><StatusBadge status={r.status} /></td>
                          <td style={{ padding: '5px 8px', color: '#666' }}>{fmtMs(r.processingMs)}</td>
                          <td style={{ padding: '5px 8px' }}>
                            {r.status === 'PASSED' && r.suggestionId && (
                              <a
                                href={`/suggestion-chart/${r.suggestionId}`}
                                style={{ color: '#42a5f5', fontSize: 11 }}
                              >
                                View chart →
                              </a>
                            )}
                            {r.status === 'ERROR' && r.errorMessage && (
                              <span style={{ color: '#ef9a9a', fontSize: 11 }} title={r.errorMessage}>
                                {r.errorMessage.substring(0, 60)}{r.errorMessage.length > 60 ? '…' : ''}
                              </span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
