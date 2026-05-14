import { useEffect, useState } from 'react';
import { getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';

type SimRun = {
  id: number;
  run_id?: string;
  runId?: string;
  strategy_name?: string;
  strategyName?: string;
  total_trades?: number;
  totalTrades?: number;
  wins?: number;
  losses?: number;
  total_pnl_pct?: number;
  totalPnlPct?: number;
};

type Props = { onSelectSymbol: (s: string) => void };

export default function SimulatedTradesPanel({ onSelectSymbol }: Props) {
  const [runs, setRuns] = useState<SimRun[]>([]);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setLoading(true);
    fetch(getApiUrl('/api/simulation-results').toString(), withAuth())
      .then(r => (r.ok ? r.json() : []))
      .then(d => setRuns(Array.isArray(d) ? d : d?.runs ?? []))
      .finally(() => setLoading(false));
  }, []);

  const groups: Record<string, SimRun[]> = {};
  for (const r of runs) {
    const name = (r.strategy_name || r.strategyName || 'unknown').toString();
    (groups[name] ||= []).push(r);
  }

  if (loading) return <div style={{ padding: 8, fontSize: 12, color: '#888' }}>Loading…</div>;
  if (Object.keys(groups).length === 0)
    return <div style={{ padding: 8, fontSize: 12, color: '#888' }}>No simulated runs.</div>;

  return (
    <div>
      {Object.entries(groups).map(([name, list]) => (
        <div key={name}>
          <div
            onClick={() => setExpanded(e => ({ ...e, [name]: !e[name] }))}
            style={{
              padding: '6px 10px',
              cursor: 'pointer',
              fontSize: 12,
              fontWeight: 600,
              background: '#f5f5f5'
            }}
          >
            {expanded[name] ? '▾' : '▸'}{' '}
            {name.length > 50 ? name.substring(0, 50) + '…' : name} ({list.length})
          </div>
          {expanded[name] &&
            list.map(r => {
              const id = r.id;
              const total = r.total_trades ?? r.totalTrades ?? 0;
              const pnl = r.total_pnl_pct ?? r.totalPnlPct ?? 0;
              return (
                <div
                  key={id}
                  style={{
                    padding: '4px 18px',
                    fontSize: 11,
                    color: '#444',
                    borderBottom: '1px solid #f0f0f0'
                  }}
                >
                  run #{id} · {total} trades · {Number(pnl).toFixed(2)}%
                </div>
              );
            })}
        </div>
      ))}
    </div>
  );
}
