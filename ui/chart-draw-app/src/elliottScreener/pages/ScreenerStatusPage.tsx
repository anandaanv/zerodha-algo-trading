import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { SymbolStatus, ElliottScreener, ElliottTradeSuggestion } from '../types';
import { fetchSymbolStatus, getScreener, triggerRun, getSuggestion, triggerRunForSymbol } from '../api';
import SuggestionCard from '../components/SuggestionCard';

const STATUS_COLOR: Record<string, string> = {
  PASSED: '#2e7d32',
  FAILED: '#e65100',
  SKIPPED: '#555',
  ERROR: '#b71c1c',
};

const STATUS_LABEL: Record<string, string> = {
  PASSED: 'Passed',
  FAILED: 'No Setup',
  SKIPPED: 'Skipped',
  ERROR: 'Error',
};

const SUGGESTION_STATE_COLOR: Record<string, string> = {
  PROPOSED: '#1565c0',
  ANTICIPATORY: '#6a1b9a',
  ACTIVE: '#2e7d32',
  SUCCESSFUL: '#1b5e20',
  FAILED: '#b71c1c',
  REJECTED: '#555',
};

function timeAgo(iso: string | null): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}

function fmtMs(ms: number | null): string {
  if (ms == null) return '—';
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
}

export default function ScreenerStatusPage() {
  const { id } = useParams<{ id: string }>();
  const screenerId = Number(id);
  const navigate = useNavigate();

  const [screener, setScreener] = useState<ElliottScreener | null>(null);
  const [symbols, setSymbols] = useState<SymbolStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);
  const [runningSymbol, setRunningSymbol] = useState<string | null>(null);
  const [filter, setFilter] = useState<string>('ALL');

  // Expanded suggestion state: symbol -> full suggestion (loaded lazily)
  const [expandedSymbol, setExpandedSymbol] = useState<string | null>(null);
  const [loadedSuggestions, setLoadedSuggestions] = useState<Record<string, ElliottTradeSuggestion>>({});
  const [loadingSuggestion, setLoadingSuggestion] = useState<string | null>(null);

  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback(async () => {
    try {
      const [sc, st] = await Promise.all([
        getScreener(screenerId),
        fetchSymbolStatus(screenerId),
      ]);
      setScreener(sc);
      setSymbols(st);
      setError(null);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [screenerId]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    pollRef.current = setInterval(load, 10000);
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
  }, [load]);

  const handleToggleExpand = async (s: SymbolStatus) => {
    if (!s.suggestionId) return;
    if (expandedSymbol === s.symbol) {
      setExpandedSymbol(null);
      return;
    }
    setExpandedSymbol(s.symbol);
    if (!loadedSuggestions[s.symbol]) {
      setLoadingSuggestion(s.symbol);
      try {
        const suggestion = await getSuggestion(s.suggestionId);
        setLoadedSuggestions(prev => ({ ...prev, [s.symbol]: suggestion }));
      } catch {
        // silently fail — no card shown
      } finally {
        setLoadingSuggestion(null);
      }
    }
  };

  const handleSuggestionUpdated = useCallback((updated: ElliottTradeSuggestion) => {
    setLoadedSuggestions(prev => ({ ...prev, [updated.symbol]: updated }));
    // Refresh status to pick up new state
    load();
  }, [load]);

  const handleRunNow = async () => {
    setRunning(true);
    setRunError(null);
    try {
      await triggerRun(screenerId);
      await load();
    } catch (e: any) {
      setRunError(e.message);
    } finally {
      setRunning(false);
    }
  };

  const handleRunSymbol = async (symbol: string) => {
    setRunningSymbol(symbol);
    setRunError(null);
    try {
      await triggerRunForSymbol(screenerId, symbol);
      await load();
    } catch (e: any) {
      setRunError(e.message);
    } finally {
      setRunningSymbol(null);
    }
  };

  const counts = symbols.reduce((acc, s) => {
    const k = s.lastStatus ?? 'NEVER';
    acc[k] = (acc[k] || 0) + 1;
    return acc;
  }, {} as Record<string, number>);
  const activeCount = symbols.filter(s =>
    s.suggestionState && ['PROPOSED', 'ANTICIPATORY', 'ACTIVE'].includes(s.suggestionState)
  ).length;

  const filtered = filter === 'ALL' ? symbols
    : filter === 'ACTIVE' ? symbols.filter(s => s.suggestionState && ['PROPOSED', 'ANTICIPATORY', 'ACTIVE'].includes(s.suggestionState))
    : filter === 'NEVER' ? symbols.filter(s => !s.lastStatus)
    : symbols.filter(s => s.lastStatus === filter);

  const colStyle: React.CSSProperties = { padding: '10px 14px', fontSize: 13 };
  const headerColStyle: React.CSSProperties = { ...colStyle, color: '#666', fontSize: 11, fontWeight: 600, textTransform: 'uppercase' as const, borderBottom: '1px solid #333' };

  return (
    <div style={{ background: '#1a1a1a', minHeight: '100vh', padding: 24, color: '#fff' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 20 }}>
        <button
          onClick={() => navigate('/elliott-screener')}
          style={{ background: '#333', color: '#aaa', border: 'none', borderRadius: 4, padding: '6px 14px', cursor: 'pointer', fontSize: 13 }}
        >
          ← Back
        </button>
        <h1 style={{ margin: 0, color: '#90caf9', fontSize: '1.4rem' }}>
          {screener?.name ?? 'Screener Status'}
        </h1>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
          {screener?.lastRunAt && (
            <span style={{ color: '#666', fontSize: 12 }}>Last run: {timeAgo(screener.lastRunAt)}</span>
          )}
          <button
            onClick={handleRunNow}
            disabled={running}
            style={{ background: running ? '#333' : '#1565c0', color: '#fff', border: 'none', borderRadius: 4, padding: '7px 18px', cursor: running ? 'default' : 'pointer', fontSize: 13 }}
          >
            {running ? 'Running...' : 'Run Now'}
          </button>
        </div>
      </div>

      {runError && <p style={{ color: '#ef9a9a', margin: '0 0 12px' }}>{runError}</p>}
      {error && <p style={{ color: '#ef9a9a', margin: '0 0 12px' }}>{error}</p>}

      {/* Summary filter bar */}
      <div style={{ display: 'flex', gap: 12, marginBottom: 20, flexWrap: 'wrap' }}>
        {[
          { key: 'ALL', label: 'All', count: symbols.length, color: '#aaa' },
          { key: 'PASSED', label: 'Passed', count: counts['PASSED'] || 0, color: '#4caf50' },
          { key: 'ACTIVE', label: 'Active Suggestions', count: activeCount, color: '#42a5f5' },
          { key: 'FAILED', label: 'No Setup', count: counts['FAILED'] || 0, color: '#ff7043' },
          { key: 'ERROR', label: 'Errors', count: counts['ERROR'] || 0, color: '#ef5350' },
          { key: 'NEVER', label: 'Never Scanned', count: counts['NEVER'] || 0, color: '#555' },
        ].map(({ key, label, count, color }) => (
          <div
            key={key}
            onClick={() => setFilter(key)}
            style={{
              background: filter === key ? '#2a2a2a' : '#1e1e1e',
              border: `1px solid ${filter === key ? color : '#333'}`,
              borderRadius: 6, padding: '8px 16px', cursor: 'pointer',
              textAlign: 'center', minWidth: 80,
            }}
          >
            <div style={{ fontSize: 20, fontWeight: 700, color }}>{count}</div>
            <div style={{ fontSize: 11, color: '#888', marginTop: 2 }}>{label}</div>
          </div>
        ))}
      </div>

      {/* Symbol list */}
      {loading ? (
        <p style={{ color: '#aaa' }}>Loading...</p>
      ) : (
        <div style={{ background: '#1e1e1e', border: '1px solid #333', borderRadius: 8, overflow: 'hidden' }}>
          {/* Header row */}
          <div style={{ display: 'grid', gridTemplateColumns: '160px 110px 110px 80px 130px 80px 1fr', borderBottom: '1px solid #333' }}>
            {['Symbol', 'Last Status', 'Last Scanned', 'Time', 'Suggestion', 'Direction', ''].map(h => (
              <div key={h} style={headerColStyle}>{h}</div>
            ))}
          </div>

          {filtered.length === 0 && (
            <div style={{ padding: '20px 14px', color: '#555', textAlign: 'center' }}>No symbols match this filter.</div>
          )}

          {filtered.map(s => {
            const isExpandable = !!s.suggestionId;
            const isExpanded = expandedSymbol === s.symbol;
            const suggestion = loadedSuggestions[s.symbol];

            return (
              <div key={s.symbol} style={{ borderBottom: '1px solid #222' }}>
                {/* Symbol row */}
                <div
                  onClick={() => isExpandable && handleToggleExpand(s)}
                  style={{
                    display: 'grid',
                    gridTemplateColumns: '160px 110px 110px 80px 130px 80px 1fr',
                    cursor: isExpandable ? 'pointer' : 'default',
                    background: isExpanded ? '#242424' : 'transparent',
                  }}
                  onMouseEnter={e => { if (isExpandable) (e.currentTarget as HTMLDivElement).style.background = '#242424'; }}
                  onMouseLeave={e => { if (!isExpanded) (e.currentTarget as HTMLDivElement).style.background = 'transparent'; }}
                >
                  <div style={{ ...colStyle, fontWeight: 600, color: '#fff', display: 'flex', alignItems: 'center', gap: 6 }}>
                    {s.symbol}
                    {isExpandable && <span style={{ color: '#555', fontSize: 10 }}>{isExpanded ? '▲' : '▼'}</span>}
                  </div>
                  <div style={colStyle}>
                    {s.lastStatus ? (
                      <span style={{ background: STATUS_COLOR[s.lastStatus], color: '#fff', borderRadius: 3, padding: '2px 8px', fontSize: 11, fontWeight: 600 }}>
                        {STATUS_LABEL[s.lastStatus] ?? s.lastStatus}
                      </span>
                    ) : <span style={{ color: '#444', fontSize: 11 }}>Never</span>}
                  </div>
                  <div style={{ ...colStyle, color: '#888' }}>{timeAgo(s.lastScannedAt)}</div>
                  <div style={{ ...colStyle, color: '#666' }}>{fmtMs(s.processingMs)}</div>
                  <div style={colStyle}>
                    {s.suggestionState ? (
                      <span style={{ background: SUGGESTION_STATE_COLOR[s.suggestionState] ?? '#333', color: '#fff', borderRadius: 3, padding: '2px 8px', fontSize: 11, fontWeight: 600 }}>
                        {s.suggestionState}
                      </span>
                    ) : <span style={{ color: '#444' }}>—</span>}
                  </div>
                  <div style={{ ...colStyle, color: s.suggestionDirection === 'LONG' ? '#4caf50' : s.suggestionDirection === 'SHORT' ? '#ef5350' : '#444' }}>
                    {s.suggestionDirection ?? '—'}
                  </div>
                  <div style={colStyle}>
                    {loadingSuggestion === s.symbol ? (
                      <span style={{ color: '#666', fontSize: 11 }}>Loading...</span>
                    ) : (
                      <button
                        onClick={(e) => { e.stopPropagation(); handleRunSymbol(s.symbol); }}
                        disabled={runningSymbol === s.symbol}
                        style={{
                          background: runningSymbol === s.symbol ? '#333' : '#0d2137',
                          color: '#90caf9',
                          border: '1px solid #1565c0',
                          borderRadius: 3,
                          padding: '3px 10px',
                          cursor: runningSymbol === s.symbol ? 'default' : 'pointer',
                          fontSize: 11,
                        }}
                      >
                        {runningSymbol === s.symbol ? '...' : '▶ Run'}
                      </button>
                    )}
                  </div>
                </div>

                {/* Expanded suggestion card */}
                {isExpanded && (
                  <div style={{ padding: '0 16px 8px', background: '#1a1a1a' }}>
                    {loadingSuggestion === s.symbol && (
                      <p style={{ color: '#aaa', fontSize: 13 }}>Loading suggestion...</p>
                    )}
                    {suggestion && (
                      <SuggestionCard
                        suggestion={suggestion}
                        onUpdated={handleSuggestionUpdated}
                      />
                    )}
                    {!suggestion && loadingSuggestion !== s.symbol && (
                      <p style={{ color: '#666', fontSize: 13 }}>Could not load suggestion details.</p>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
