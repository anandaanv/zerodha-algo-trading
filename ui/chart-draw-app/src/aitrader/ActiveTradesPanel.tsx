import { useEffect, useState } from 'react';
import { getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';

type PaperTrade = {
  id: number;
  symbol: string;
  direction: string;
  entryPrice: number;
  sl: number;
  target: number;
  openedAt: string;
  status: string;
};

type Props = { onSelectSymbol: (s: string) => void; refreshTick?: number };

export default function ActiveTradesPanel({ onSelectSymbol, refreshTick }: Props) {
  const [trades, setTrades] = useState<PaperTrade[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const r = await fetch(
        getApiUrl('/api/ai-trader/paper-trades?status=OPEN').toString(),
        withAuth()
      );
      if (r.ok) setTrades(await r.json());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [refreshTick]);

  if (loading && trades.length === 0)
    return <div style={{ padding: 8, fontSize: 12, color: '#888' }}>Loading…</div>;
  if (trades.length === 0)
    return <div style={{ padding: 8, fontSize: 12, color: '#888' }}>No active trades.</div>;

  return (
    <div>
      {trades.map(t => (
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
            @{t.entryPrice} · SL {t.sl} · TP {t.target}
          </span>
        </div>
      ))}
    </div>
  );
}
