import React, { useState, useEffect, useRef } from 'react';
import { initiateScan, fetchScans, fetchScan } from '../../elliottScreener/api';
import ScanResultCard from '../components/ScanResultCard';

const TIMEFRAMES = ['1w', '1d', '4h', '1h', '30min', '15min'];

export default function ScanPage() {
  const [symbol, setSymbol] = useState('');
  const [primaryTf, setPrimaryTf] = useState('1d');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [scans, setScans] = useState<any[]>([]);
  const [pollingIds, setPollingIds] = useState<Set<number>>(new Set());
  const [verboseLogging, setVerboseLogging] = useState(false);
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    loadScans();
  }, []);

  // Poll every 3s while there are pending/running scans
  useEffect(() => {
    if (pollingIds.size === 0) {
      if (pollingRef.current) clearInterval(pollingRef.current);
      return;
    }
    pollingRef.current = setInterval(async () => {
      const updatedScans = [...scans];
      let anyStillRunning = false;
      for (const scanId of Array.from(pollingIds)) {
        try {
          const updated = await fetchScan(scanId);
          const idx = updatedScans.findIndex(s => s.id === scanId);
          if (idx >= 0) updatedScans[idx] = updated;
          if (updated.status === 'PENDING' || updated.status === 'RUNNING') {
            anyStillRunning = true;
          } else {
            setPollingIds(prev => { const n = new Set(prev); n.delete(scanId); return n; });
          }
        } catch {}
      }
      setScans([...updatedScans]);
      if (!anyStillRunning) {
        if (pollingRef.current) clearInterval(pollingRef.current);
      }
    }, 3000);
    return () => { if (pollingRef.current) clearInterval(pollingRef.current); };
  }, [pollingIds, scans]);

  async function loadScans() {
    try {
      const data = await fetchScans();
      setScans(data);
      // Start polling for any in-progress scans
      const inProgress = data.filter((s: any) => s.status === 'PENDING' || s.status === 'RUNNING');
      if (inProgress.length > 0) {
        setPollingIds(new Set(inProgress.map((s: any) => s.id)));
      }
    } catch (e) {
      console.error('Failed to load scans', e);
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!symbol.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const scan = await initiateScan(symbol.trim().toUpperCase(), primaryTf, verboseLogging);
      setScans(prev => [scan, ...prev]);
      setPollingIds(prev => new Set([...Array.from(prev), scan.id]));
      setSymbol('');
    } catch (err: any) {
      if (err.message?.startsWith('RATE_LIMIT:')) {
        setError(err.message.replace('RATE_LIMIT:', ''));
      } else {
        setError(err.message || 'Failed to initiate scan');
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{ maxWidth: 900, margin: '0 auto', padding: '24px 16px', fontFamily: 'monospace' }}>
      <h2 style={{ color: '#90caf9', marginBottom: 20 }}>Elliott Wave On-Demand Scan</h2>

      {/* Form */}
      <form onSubmit={handleSubmit} style={{
        background: '#16213e', border: '1px solid #333', borderRadius: 8,
        padding: 16, marginBottom: 24, display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap',
      }}>
        <div style={{ flex: '1 1 200px' }}>
          <label style={{ color: '#aaa', fontSize: 12, display: 'block', marginBottom: 4 }}>Symbol</label>
          <input
            value={symbol}
            onChange={e => setSymbol(e.target.value.toUpperCase())}
            placeholder="e.g. RELIANCE"
            required
            style={{
              width: '100%', background: '#0d1117', border: '1px solid #444', borderRadius: 4,
              color: '#e0e0e0', padding: '8px 10px', fontSize: 14, boxSizing: 'border-box',
            }}
          />
        </div>
        <div style={{ flex: '0 1 160px' }}>
          <label style={{ color: '#aaa', fontSize: 12, display: 'block', marginBottom: 4 }}>Primary Timeframe</label>
          <select
            value={primaryTf}
            onChange={e => setPrimaryTf(e.target.value)}
            style={{
              width: '100%', background: '#0d1117', border: '1px solid #444', borderRadius: 4,
              color: '#e0e0e0', padding: '8px 10px', fontSize: 14,
            }}
          >
            {TIMEFRAMES.map(tf => <option key={tf} value={tf}>{tf}</option>)}
          </select>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', gap: 4 }}>
          <label style={{ color: '#aaa', fontSize: 12, display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={verboseLogging}
              onChange={e => setVerboseLogging(e.target.checked)}
              style={{ accentColor: '#90caf9' }}
            />
            Verbose Logging
          </label>
          <button
            type="submit"
            disabled={loading}
            style={{
              background: loading ? '#333' : '#1565c0', color: loading ? '#888' : '#fff',
              border: 'none', borderRadius: 4, padding: '9px 20px', cursor: loading ? 'default' : 'pointer',
              fontSize: 14, fontWeight: 600, whiteSpace: 'nowrap',
            }}
          >
            {loading ? 'Scanning...' : 'Run Scan'}
          </button>
        </div>
      </form>

      {/* Error */}
      {error && (
        <div style={{
          background: '#3a1a1a', border: '1px solid #ef5350', borderRadius: 4,
          padding: '10px 14px', color: '#ef9a9a', marginBottom: 16, fontSize: 13,
        }}>
          {error}
        </div>
      )}

      {/* History header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h3 style={{ color: '#e0e0e0', margin: 0, fontSize: 16 }}>
          Scan History {pollingIds.size > 0 && <span style={{ color: '#42a5f5', fontSize: 13 }}>⟳ polling...</span>}
        </h3>
        <button
          onClick={loadScans}
          style={{
            background: 'transparent', color: '#90caf9', border: '1px solid #1565c0',
            borderRadius: 4, padding: '4px 10px', cursor: 'pointer', fontSize: 12,
          }}
        >
          Refresh
        </button>
      </div>

      {/* Scan list */}
      {scans.length === 0 ? (
        <div style={{ color: '#666', fontSize: 14 }}>No scans yet. Run your first scan above.</div>
      ) : (
        scans.map(scan => <ScanResultCard key={scan.id} scan={scan} />)
      )}
    </div>
  );
}
