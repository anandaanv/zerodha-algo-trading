import { useEffect, useState } from 'react';
import { getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';

type WatchTrade = {
  id: number;
  symbol: string;
  direction: string;
  entry: number;
  sl: number;
  target: number;
  rr: number;
  confidence: number;
  triggerType: string;
  rationale: string;
  status: string;
  validityUntil: string;
};

type Props = { symbol?: string; onSelectSymbol: (s: string) => void; refreshTick?: number };

export default function WatchTradesPanel({ symbol, onSelectSymbol, refreshTick }: Props) {
  const [trades, setTrades] = useState<WatchTrade[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const r = await fetch(
        getApiUrl('/api/watch-trades?status=WATCHING').toString(),
        withAuth()
      );
      if (r.ok) setTrades(await r.json());
    } catch (err) {
      console.error('Error loading watch trades:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [refreshTick]);

  const filtered = symbol ? trades.filter(t => t.symbol === symbol) : trades;

  if (loading && filtered.length === 0)
    return <div style={{ padding: 8, fontSize: 12, color: '#888' }}>Loading…</div>;
  if (filtered.length === 0)
    return <div style={{ padding: 8, fontSize: 12, color: '#888' }}>No watching trades{symbol ? ` for ${symbol}` : ''}.</div>;

  return (
    <div>
      {filtered.map(t => (
        <div
          key={t.id}
          onClick={() => onSelectSymbol(t.symbol)}
          style={{
            padding: '6px 10px',
            borderBottom: '1px solid #eee',
            cursor: 'pointer',
            fontSize: 12,
            display: 'flex',
            justifyContent: 'space-between'
          }}
        >
          <span
            style={{
              fontWeight: 600,
              color: t.direction === 'LONG' ? '#2e7d32' : '#c62828'
            }}
          >
            {t.symbol} {t.direction}
          </span>
          <span style={{ color: '#666' }}>
            E {t.entry} · SL {t.sl} · TP {t.target}
          </span>
        </div>
      ))}
    </div>
  );
}
