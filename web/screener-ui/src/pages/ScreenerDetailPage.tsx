import React, { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ScreenerResponse, getScreener, runScreener } from '../api';

function tryParse(json: string): any {
  try { return JSON.parse(json || '{}'); } catch { return null; }
}

export default function ScreenerDetailPage() {
  const { id } = useParams();
  const screenerId = Number(id);
  const [data, setData] = useState<ScreenerResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // run form state
  const [symbol, setSymbol] = useState('NIFTY');
  const [nowIndex, setNowIndex] = useState<number>(0);
  const [timeframe, setTimeframe] = useState<string>('');
  const [running, setRunning] = useState(false);
  const [runMsg, setRunMsg] = useState<string | null>(null);

  useEffect(() => {
    let mounted = true;
    setLoading(true);
    getScreener(screenerId)
      .then(res => { if (mounted) setData(res); })
      .catch(err => setError(err.message || 'Failed to load screener'))
      .finally(() => setLoading(false));
    return () => { mounted = false; };
  }, [screenerId]);

  const cfg = useMemo(() => tryParse(data?.configJson || ''), [data]);

  async function onRun(e: React.FormEvent) {
    e.preventDefault();
    setRunMsg(null);
    setRunning(true);
    try {
      await runScreener(screenerId, { symbol, nowIndex, timeframe: timeframe || undefined });
      setRunMsg('Run triggered successfully.');
    } catch (err: any) {
      setRunMsg(err.message || 'Failed to run.');
    } finally {
      setRunning(false);
    }
  }

  return (
    <div className="card">
      <div className="toolbar" style={{ justifyContent: 'space-between' }}>
        <h1 style={{ margin: 0 }}>Screener #{screenerId}</h1>
        <Link className="btn" to="/screeners">Back to list</Link>
      </div>

      {loading && <p className="muted">Loading…</p>}
      {error && <p className="error">{error}</p>}

      {data && (
        <>
          <div className="row">
            <strong>Timeframe:</strong> {data.timeframe || '-'}
          </div>

          <div className="row">
            <div className="section-title">Workflow</div>
            <pre style={{ background: '#0b1324', padding: 12, borderRadius: 10, border: '1px solid #334155', overflowX: 'auto' }}>
{JSON.stringify(cfg?.workflow ?? [], null, 2)}
            </pre>
          </div>

          <div className="row">
            <div className="section-title">Mapping</div>
            <pre style={{ background: '#0b1324', padding: 12, borderRadius: 10, border: '1px solid #334155', overflowX: 'auto' }}>
{JSON.stringify(cfg?.mapping ?? {}, null, 2)}
            </pre>
          </div>

          <div className="row">
            <div className="section-title">Script</div>
            <pre style={{ background: '#0b1324', padding: 12, borderRadius: 10, border: '1px solid #334155', overflowX: 'auto' }}>
{data.script || ''}
            </pre>
          </div>

          <div className="row">
            <div className="section-title">Prompt JSON</div>
            <pre style={{ background: '#0b1324', padding: 12, borderRadius: 10, border: '1px solid #334155', overflowX: 'auto' }}>
{data.promptJson || '{}'}
            </pre>
          </div>

          <div className="row">
            <div className="section-title">Charts</div>
            <pre style={{ background: '#0b1324', padding: 12, borderRadius: 10, border: '1px solid #334155', overflowX: 'auto' }}>
{data.chartsJson || '[]'}
            </pre>
          </div>

          <div className="row">
            <div className="section-title">Run Screener</div>
            <form onSubmit={onRun} className="grid">
              <div>
                <label>Symbol</label>
                <input value={symbol} onChange={e => setSymbol(e.target.value)} placeholder="e.g. NIFTY" />
              </div>
              <div>
                <label>Now Index</label>
                <input
                  type="number"
                  value={nowIndex}
                  onChange={e => setNowIndex(Number(e.target.value))}
                  placeholder="e.g. 0 or series end index"
                />
              </div>
              <div>
                <label>Timeframe Override (optional)</label>
                <input value={timeframe} onChange={e => setTimeframe(e.target.value)} placeholder="e.g. DAY_1" />
              </div>
              <div className="toolbar">
                <button type="submit" className="btn primary" disabled={running}>{running ? 'Running…' : 'Run'}</button>
              </div>
            </form>
            {runMsg && <div className="row" style={{ marginTop: 8 }}>
              <span className={/successfully/i.test(runMsg) ? 'success' : 'error'}>{runMsg}</span>
            </div>}
          </div>
        </>
      )}
    </div>
  );
}
