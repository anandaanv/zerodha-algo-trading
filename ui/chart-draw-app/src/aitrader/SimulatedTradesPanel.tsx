import { useEffect, useState, useCallback } from 'react';
import { getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';

type SimRun = {
  id: number;
  run_id?: string; runId?: string;
  strategy_name?: string; strategyName?: string;
  total_trades?: number; totalTrades?: number;
  wins?: number; losses?: number;
  total_pnl_pct?: number; totalPnlPct?: number;
};

type SimTrade = {
  id: number;
  symbol: string;
  direction: string;
  entryPrice?: number; entry_price?: number;
  stopInitial?: number; stop_initial?: number;
  targetInitial?: number; target_initial?: number;
  exitPrice?: number; exit_price?: number;
  entryTime?: string; entry_time?: string;
  exitTime?: string; exit_time?: string;
  exitReason?: string; exit_reason?: string;
  pnlPct?: number; pnl_pct?: number;
  patternType?: string; pattern_type?: string;
};

type Replay = {
  id: number;
  aiVerdict: string;
  aiDirection: string | null;
  aiEntry: number | null; aiSl: number | null; aiTarget: number | null;
  aiConfidence: number;
  aiReasoning: string;
  totalCostUsd: number;
  durationMs: number;
  modelUsed: string;
};

type Props = {
  symbol?: string;
  onSelectSymbol: (s: string) => void;
  /**
   * Invoked when the trader clicks the trade row. Receives the full trade object
   * so the parent can pan/decorate the chart for the trade window without
   * triggering a symbol-change re-render. When omitted, falls back to onSelectSymbol.
   */
  onSelectTrade?: (trade: SimTrade) => void;
};

const f = (n: any, dp = 2) => (n == null ? '-' : Number(n).toFixed(dp));
const truncate = (s: string, n: number) => (s && s.length > n ? s.substring(0, n) + '…' : s);

export default function SimulatedTradesPanel({ symbol, onSelectSymbol, onSelectTrade }: Props) {
  const [runs, setRuns] = useState<SimRun[]>([]);
  const [loading, setLoading] = useState(false);
  const [groupExpanded, setGroupExpanded] = useState<Record<string, boolean>>({});
  const [runExpanded, setRunExpanded] = useState<Record<number, boolean>>({});
  const [tradesByRun, setTradesByRun] = useState<Record<number, SimTrade[]>>({});
  const [tradesLoading, setTradesLoading] = useState<Set<number>>(new Set());
  const [replays, setReplays] = useState<Record<number, Replay>>({});
  const [replayLoading, setReplayLoading] = useState<Set<number>>(new Set());

  // Re-fetch runs whenever the selected symbol changes. Backend filters server-side
  // to runs that have at least one trade for the symbol.
  useEffect(() => {
    setLoading(true);
    const url = symbol
      ? getApiUrl(`/api/simulation-results?symbol=${encodeURIComponent(symbol)}`)
      : getApiUrl('/api/simulation-results');
    fetch(url.toString(), withAuth())
      .then(r => r.ok ? r.json() : [])
      .then(d => setRuns(Array.isArray(d) ? d : (d?.runs ?? [])))
      .finally(() => setLoading(false));
    // Symbol change → invalidate the per-run trade cache so newly-expanded rows
    // re-fetch with the right symbol filter applied.
    setTradesByRun({});
    setRunExpanded({});
  }, [symbol]);

  const fetchTrades = useCallback(async (run: SimRun) => {
    const cacheKey = run.id;
    if (tradesByRun[cacheKey]) return;
    // The trades endpoint takes the STRING run_id (e.g. "candle-short-20260512-..."),
    // not the numeric DB id. Fall back to DB id if run_id is missing for some reason.
    const runIdParam = run.run_id || run.runId || String(run.id);
    setTradesLoading(s => new Set(s).add(cacheKey));
    try {
      const symParam = symbol ? `&symbol=${encodeURIComponent(symbol)}` : '';
      const url = getApiUrl(`/api/simulation-results/${encodeURIComponent(runIdParam)}/trades?page=0&size=200${symParam}`);
      const r = await fetch(url.toString(), withAuth());
      if (r.ok) {
        const data = await r.json();
        const list = Array.isArray(data) ? data : (data?.trades ?? data?.content ?? []);
        setTradesByRun(prev => ({ ...prev, [cacheKey]: list }));
      } else {
        console.warn(`fetchTrades failed for ${runIdParam}: HTTP ${r.status}`);
      }
    } finally {
      setTradesLoading(s => { const n = new Set(s); n.delete(cacheKey); return n; });
    }
  }, [tradesByRun, symbol]);

  const toggleRun = (run: SimRun) => {
    const runId = run.id;
    setRunExpanded(e => ({ ...e, [runId]: !e[runId] }));
    if (!runExpanded[runId]) fetchTrades(run);
  };

  const handleReplay = useCallback(async (tradeId: number) => {
    setReplayLoading(s => new Set(s).add(tradeId));
    try {
      const r = await fetch(getApiUrl('/api/ai-trader/replay/run').toString(), {
        ...withAuth(),
        method: 'POST',
        headers: { ...withAuth().headers, 'Content-Type': 'application/json' },
        body: JSON.stringify({ simulationTradeId: tradeId }),
      });
      if (!r.ok) {
        const err = await r.text();
        setReplays(prev => ({ ...prev, [tradeId]: {
          id: -1, aiVerdict: 'ERROR', aiDirection: null,
          aiEntry: null, aiSl: null, aiTarget: null,
          aiConfidence: 0, aiReasoning: `HTTP ${r.status}: ${truncate(err, 200)}`,
          totalCostUsd: 0, durationMs: 0, modelUsed: '',
        }}));
        return;
      }
      const data = await r.json();
      setReplays(prev => ({ ...prev, [tradeId]: data }));
    } catch (e: any) {
      setReplays(prev => ({ ...prev, [tradeId]: {
        id: -1, aiVerdict: 'ERROR', aiDirection: null,
        aiEntry: null, aiSl: null, aiTarget: null,
        aiConfidence: 0, aiReasoning: e?.message || 'Failed',
        totalCostUsd: 0, durationMs: 0, modelUsed: '',
      }}));
    } finally {
      setReplayLoading(s => { const n = new Set(s); n.delete(tradeId); return n; });
    }
  }, []);

  if (loading) return <div style={{padding: 8, fontSize: 12, color: '#888'}}>Loading…</div>;
  if (runs.length === 0) return <div style={{padding: 8, fontSize: 12, color: '#888'}}>No simulated runs.</div>;

  // Group by strategy
  const groups: Record<string, SimRun[]> = {};
  for (const r of runs) {
    const name = (r.strategy_name || r.strategyName || 'unknown').toString();
    (groups[name] ||= []).push(r);
  }

  return (
    <div>
      {Object.entries(groups).map(([name, list]) => {
        const isOpen = !!groupExpanded[name];
        return (
          <div key={name}>
            <div
              onClick={() => setGroupExpanded(e => ({...e, [name]: !e[name]}))}
              style={{
                padding: '6px 10px', cursor: 'pointer', fontSize: 12,
                fontWeight: 600, background: '#f5f5f5',
              }}>
              {isOpen ? '▾' : '▸'} {truncate(name, 60)} ({list.length})
            </div>
            {isOpen && list.map(r => {
              const runId = r.id;
              const total = r.total_trades ?? r.totalTrades ?? 0;
              const pnl = r.total_pnl_pct ?? r.totalPnlPct ?? 0;
              const trades = tradesByRun[runId] || [];
              const isRunOpen = !!runExpanded[runId];
              return (
                <div key={runId}>
                  <div
                    onClick={() => toggleRun(r)}
                    style={{
                      padding: '4px 18px', fontSize: 11, color: '#444',
                      borderBottom: '1px solid #f0f0f0', cursor: 'pointer',
                      background: isRunOpen ? '#fafafa' : 'transparent',
                    }}>
                    {isRunOpen ? '▾' : '▸'} run #{runId} · {total} trades · {Number(pnl).toFixed(2)}%
                  </div>
                  {isRunOpen && tradesLoading.has(runId) && (
                    <div style={{padding: '4px 28px', fontSize: 11, color: '#888'}}>Loading trades…</div>
                  )}
                  {isRunOpen && !tradesLoading.has(runId) && trades.length === 0 && (
                    <div style={{padding: '4px 28px', fontSize: 11, color: '#888'}}>No trades.</div>
                  )}
                  {isRunOpen && trades.map(t => {
                    const direction = t.direction;
                    const entry = t.entryPrice ?? t.entry_price;
                    const sl = t.stopInitial ?? t.stop_initial;
                    const target = t.targetInitial ?? t.target_initial;
                    const exitPrice = t.exitPrice ?? t.exit_price;
                    const entryTime = t.entryTime ?? t.entry_time ?? '';
                    const exitTime = t.exitTime ?? t.exit_time ?? '';
                    const exitReason = t.exitReason ?? t.exit_reason ?? '';
                    const pnlPct = t.pnlPct ?? t.pnl_pct ?? 0;
                    const pattern = t.patternType ?? t.pattern_type ?? '';
                    const win = Number(pnlPct) > 0;
                    const rep = replays[t.id];
                    const replaying = replayLoading.has(t.id);
                    return (
                      <div key={t.id} style={{
                        padding: '6px 24px 6px 28px', fontSize: 11,
                        borderBottom: '1px solid #f5f5f5', background: '#fff',
                      }}>
                        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8}}>
                          <div
                            onClick={() => {
                              if (onSelectTrade) onSelectTrade(t);
                              else onSelectSymbol(t.symbol);
                            }}
                            style={{flex: 1, cursor: 'pointer'}}
                          >
                            <span style={{
                              fontWeight: 600,
                              color: direction === 'LONG' ? '#2e7d32' : '#c62828',
                            }}>{t.symbol} {direction}</span>
                            {pattern && <span style={{color: '#888', marginLeft: 6}}>· {pattern}</span>}
                            <div style={{color: '#555', marginTop: 2}}>
                              entry @ {f(entry)} · sl {f(sl)} · tp {f(target)}
                            </div>
                            <div style={{color: '#777', fontSize: 10, marginTop: 1}}>
                              {entryTime.substring(0, 16).replace('T', ' ')}
                              {exitTime && ` → ${exitTime.substring(0, 16).replace('T', ' ')}`}
                              {' · '}
                              <span style={{color: exitReason === 'TARGET' ? '#2e7d32' : exitReason === 'STOP' ? '#c62828' : '#888'}}>
                                {exitReason}
                              </span>
                              <span style={{color: win ? '#2e7d32' : '#c62828', fontWeight: 600, marginLeft: 4}}>
                                {win ? '+' : ''}{Number(pnlPct).toFixed(2)}%
                              </span>
                            </div>
                          </div>
                          <button
                            onClick={() => handleReplay(t.id)}
                            disabled={replaying}
                            style={{
                              padding: '3px 8px', fontSize: 10, border: 'none',
                              borderRadius: 3, cursor: replaying ? 'not-allowed' : 'pointer',
                              background: replaying ? '#ddd' : '#7E57C2', color: 'white',
                              whiteSpace: 'nowrap',
                            }}>
                            {replaying ? '…' : '🔬 Replay'}
                          </button>
                        </div>
                        {rep && (
                          <div style={{
                            marginTop: 6, padding: '5px 8px', borderRadius: 3, fontSize: 10,
                            background: rep.aiVerdict === 'TRADE' ? '#e8f5e9'
                                      : rep.aiVerdict === 'NO_TRADE' ? '#f5f5f5'
                                      : '#ffebee',
                            border: `1px solid ${rep.aiVerdict === 'TRADE' ? '#a5d6a7'
                                                  : rep.aiVerdict === 'NO_TRADE' ? '#ddd'
                                                  : '#ef9a9a'}`,
                          }}>
                            <div style={{fontWeight: 600, color:
                              rep.aiVerdict === 'TRADE' ? '#1b5e20'
                              : rep.aiVerdict === 'NO_TRADE' ? '#555'
                              : '#b71c1c',
                            }}>
                              AI: {rep.aiVerdict} {rep.aiConfidence ? `· conf ${(rep.aiConfidence * 100).toFixed(0)}%` : ''}
                              {rep.aiVerdict === 'TRADE' && rep.aiEntry != null && (
                                <span style={{fontWeight: 'normal', marginLeft: 6}}>
                                  ({rep.aiDirection} {f(rep.aiEntry)}/{f(rep.aiSl)}/{f(rep.aiTarget)})
                                </span>
                              )}
                            </div>
                            <div style={{color: '#444', marginTop: 2}}>{rep.aiReasoning}</div>
                            <div style={{color: '#888', marginTop: 2, fontSize: 9}}>
                              ${Number(rep.totalCostUsd || 0).toFixed(4)} · {rep.durationMs}ms · {truncate(rep.modelUsed || '', 30)}
                            </div>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              );
            })}
          </div>
        );
      })}
    </div>
  );
}
