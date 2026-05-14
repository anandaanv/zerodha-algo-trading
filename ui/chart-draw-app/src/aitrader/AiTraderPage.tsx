import { useRef, useState, useCallback } from 'react';
import TVChartContainer from '../tradingview/TVChartContainer';
import CollapsibleSection from './CollapsibleSection';
import ActiveTradesPanel from './ActiveTradesPanel';
import WatchTradesPanel from './WatchTradesPanel';
import SimulatedTradesPanel from './SimulatedTradesPanel';
import WatchlistPanel from './WatchlistPanel';
import AnnotationsPanel from './annotations/AnnotationsPanel';
import { getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';
import './AiTraderPage.css';

// TradingView resolution → backend interval mapping
const resolutionToInterval: Record<string, string> = {
  '1': 'OneMinute', '3': 'ThreeMinute', '5': 'FiveMinute',
  '15': 'FifteenMinute', '30': 'ThirtyMinute',
  '60': 'OneHour', '120': 'TwoHour',
  '1D': 'Day', 'D': 'Day', '1W': 'Week', 'W': 'Week',
};

const TAB_ID_KEY = 'aitrader_tab_id';
function getOrCreateTabId(): string {
  let id = localStorage.getItem(TAB_ID_KEY);
  if (!id) {
    id = crypto.randomUUID ? crypto.randomUUID() : `tab-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    localStorage.setItem(TAB_ID_KEY, id);
  }
  return id;
}

function isoToEpochSec(t: any): number {
  if (typeof t === 'number') return t > 1e12 ? Math.floor(t / 1000) : t;
  if (typeof t === 'string') {
    const ms = Date.parse(t);
    return isNaN(ms) ? 0 : Math.floor(ms / 1000);
  }
  return 0;
}

export default function AiTraderPage() {
  const [selectedSymbol, setSelectedSymbol] = useState<string>('WIPRO');
  const [symbolInput, setSymbolInput] = useState<string>('WIPRO');
  const [refreshTick, setRefreshTick] = useState<number>(0);
  const [aiLoading, setAiLoading] = useState(false);
  const [analyseLoading, setAnalyseLoading] = useState(false);
  const [toast, setToast] = useState<{msg: string; kind: 'success'|'error'} | null>(null);

  const chartRef = useRef<any>(null);
  const aiShapeIdsRef = useRef<any[]>([]);
  const simTradeShapeIdsRef = useRef<any[]>([]);
  const tabId = getOrCreateTabId();

  // Single source of truth for symbol changes — also bumps refreshTick so dependent panels re-fetch.
  const handleSelectSymbol = useCallback((symbol: string) => {
    if (!symbol) return;
    const up = symbol.trim().toUpperCase();
    setSelectedSymbol(up);
    setSymbolInput(up);
    setRefreshTick(t => t + 1);
  }, []);

  const commitSymbolInput = useCallback(() => {
    if (symbolInput && symbolInput.trim().toUpperCase() !== selectedSymbol) {
      handleSelectSymbol(symbolInput);
    } else {
      setSymbolInput(selectedSymbol); // normalize display
    }
  }, [symbolInput, selectedSymbol, handleSelectSymbol]);

  const showToast = useCallback((msg: string, kind: 'success'|'error' = 'success') => {
    setToast({ msg, kind });
    setTimeout(() => setToast(null), 5000);
  }, []);

  // Pan the chart to a simulated trade and draw entry/SL/TP horizontals.
  // We deliberately go through chartRef directly — no React state changes — so the
  // TVChartContainer widget does NOT remount (which would look like a page refresh).
  const handleSelectSimTrade = useCallback(async (trade: any) => {
    if (!chartRef.current) { showToast('Chart not ready', 'error'); return; }
    const chart = chartRef.current;
    const symbol = trade.symbol;
    const direction = trade.direction;
    const entry = Number(trade.entryPrice ?? trade.entry_price);
    const sl = Number(trade.stopInitial ?? trade.stop_initial);
    const target = Number(trade.targetInitial ?? trade.target_initial);
    const entryEpoch = isoToEpochSec(trade.entryTime ?? trade.entry_time);
    const exitEpoch = isoToEpochSec(trade.exitTime ?? trade.exit_time) || (entryEpoch + 7 * 86400);

    // If a stray different-symbol trade ever lands here, switch via TV API (no remount).
    if (symbol && symbol !== selectedSymbol) {
      try { chart.setSymbol?.(symbol, () => {}); } catch (e) { console.warn('setSymbol failed', e); }
    }

    // Pan to entry/exit window with a 30% buffer on each side.
    if (entryEpoch > 0) {
      const duration = Math.max(86400, exitEpoch - entryEpoch);
      const buffer = duration * 0.3;
      try {
        chart.setVisibleRange({ from: entryEpoch - buffer, to: exitEpoch + buffer });
      } catch (e) { console.warn('setVisibleRange failed', e); }
    }

    // Clear previous sim-trade shapes
    for (const id of simTradeShapeIdsRef.current) {
      try { chart.removeEntity(id); } catch {}
    }
    simTradeShapeIdsRef.current = [];

    // Draw entry/SL/TP horizontals anchored at entryEpoch.
    const drawLine = async (price: number, color: string, label: string) => {
      if (!Number.isFinite(price)) return;
      try {
        const id = await chart.createShape(
          { time: entryEpoch || Math.floor(Date.now() / 1000), price },
          { shape: 'horizontal_line', lock: false, disableSelection: false, disableSave: true, disableUndo: true, text: label }
        );
        if (id) {
          try {
            const shape = chart.getShapeById(id);
            shape?.setProperties?.({ linecolor: color, linewidth: 1, linestyle: 0, textcolor: color, showLabel: true });
          } catch {}
          simTradeShapeIdsRef.current.push(id);
        }
      } catch (e) { console.warn('drawLine failed', label, e); }
    };

    const entryColor = direction === 'LONG' ? '#2e7d32' : '#c62828';
    drawLine(entry, entryColor, `Entry ${direction}`);
    drawLine(sl, '#EF5350', 'SL');
    drawLine(target, '#26A69A', 'Target');

    const when = (trade.entryTime ?? trade.entry_time ?? '').toString().substring(0, 16).replace('T', ' ');
    showToast(`${symbol} ${direction} · ${when} · entry ${entry.toFixed(2)}`);
  }, [selectedSymbol, showToast]);

  const handleAiLevels = useCallback(async () => {
    if (!chartRef.current) { showToast('Chart not ready', 'error'); return; }
    try {
      setAiLoading(true);
      const chart = chartRef.current;
      const resolution = chart.resolution();
      const timeframe = resolutionToInterval[resolution] || 'OneHour';
      const res = await fetch(getApiUrl('/api/ai-levels/run').toString(), {
        ...withAuth(),
        method: 'POST',
        headers: { ...withAuth().headers, 'Content-Type': 'application/json' },
        body: JSON.stringify({ symbol: selectedSymbol, tabId, layoutId: 1, timeframe }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}: ${(await res.text()).substring(0, 200)}`);
      const data = await res.json();
      let levels = data.levelsJson ?? data.levels_json;
      if (typeof levels === 'string') { try { levels = JSON.parse(levels); } catch { levels = {}; } }
      levels = levels || {};

      // Clear previous AI shapes
      for (const id of aiShapeIdsRef.current) { try { chart.removeEntity(id); } catch {} }
      aiShapeIdsRef.current = [];

      let drawn = 0;
      // Horizontals (we trimmed AI Levels to horizontals-only)
      const visRange = chart.getVisibleRange?.();
      const anchorTime = visRange?.from || Math.floor(Date.now()/1000);
      for (const h of (levels.horizontal_levels || [])) {
        try {
          const price = Number(h.price);
          if (!Number.isFinite(price)) continue;
          const id = await chart.createShape(
            { time: anchorTime, price },
            { shape: 'horizontal_line', lock: false, disableSelection: false, disableSave: true, disableUndo: true, text: h.rationale || '' }
          );
          if (id) {
            try {
              const shape = chart.getShapeById(id);
              shape?.setProperties?.({ linecolor: '#26A69A', linewidth: 1, linestyle: 2, textcolor: '#26A69A', showLabel: false });
            } catch {}
            aiShapeIdsRef.current.push(id);
            drawn++;
          }
        } catch (e) { console.warn('AI horizontal failed', h, e); }
      }
      showToast(`Drew ${drawn} AI level${drawn !== 1 ? 's' : ''}. ${levels.summary || ''}`.trim());
    } catch (e: any) {
      showToast(e?.message || 'AI Levels failed', 'error');
    } finally { setAiLoading(false); }
  }, [selectedSymbol, tabId, showToast]);

  const handleAiAnalyse = useCallback(async () => {
    if (!chartRef.current) { showToast('Chart not ready', 'error'); return; }
    try {
      setAnalyseLoading(true);
      const chart = chartRef.current;
      const resolution = chart.resolution();
      const timeframe = resolutionToInterval[resolution] || 'OneHour';
      const res = await fetch(getApiUrl('/api/ai-analyse/run').toString(), {
        ...withAuth(),
        method: 'POST',
        headers: { ...withAuth().headers, 'Content-Type': 'application/json' },
        body: JSON.stringify({ symbol: selectedSymbol, tabId, layoutId: 1, timeframe }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}: ${(await res.text()).substring(0, 200)}`);
      const data = await res.json();
      const n = (data.watchTrades || data.watch_trades || []).length;
      setRefreshTick(t => t + 1);  // refresh WatchTrades + ActiveTrades panels
      showToast(`AI Analyse: created ${n} watch trade${n !== 1 ? 's' : ''}`);
    } catch (e: any) {
      showToast(e?.message || 'AI Analyse failed', 'error');
    } finally { setAnalyseLoading(false); }
  }, [selectedSymbol, tabId, showToast]);

  return (
    <div style={{ display: 'flex', flexDirection: 'row', height: '100vh', overflow: 'hidden' }}>
      {/* Left: Watchlist */}
      <div style={{ width: 260, borderRight: '1px solid #ddd', overflowY: 'auto', background: '#fff' }}>
        <WatchlistPanel
          onSelectSymbol={handleSelectSymbol}
          onBatchComplete={() => setRefreshTick(t => t + 1)}
        />
      </div>

      {/* Center: toolbar + chart */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', borderRight: '1px solid #ddd', background: '#fff' }}>
        {/* Toolbar */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8,
          padding: '6px 12px', borderBottom: '1px solid #e0e0e0', background: '#fafafa',
          fontSize: 13,
        }}>
          <input
            value={symbolInput}
            onChange={(e) => setSymbolInput(e.target.value.toUpperCase())}
            onBlur={commitSymbolInput}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.currentTarget.blur(); } }}
            placeholder="Symbol"
            style={{
              width: 120, padding: '4px 8px', fontSize: 13, fontWeight: 600,
              border: '1px solid #ccc', borderRadius: 4, marginRight: 8,
              textTransform: 'uppercase',
            }}
          />
          <button
            onClick={handleAiLevels} disabled={aiLoading}
            style={{
              padding: '5px 12px', fontSize: 12, border: 'none', borderRadius: 4,
              background: aiLoading ? '#bbb' : '#26A69A', color: 'white',
              cursor: aiLoading ? 'not-allowed' : 'pointer',
            }}>{aiLoading ? 'Loading…' : '✨ AI Levels'}</button>
          <button
            onClick={handleAiAnalyse} disabled={analyseLoading}
            style={{
              padding: '5px 12px', fontSize: 12, border: 'none', borderRadius: 4,
              background: analyseLoading ? '#bbb' : '#7E57C2', color: 'white',
              cursor: analyseLoading ? 'not-allowed' : 'pointer',
            }}>{analyseLoading ? 'Analysing…' : '🔍 AI Analyse'}</button>
        </div>
        {/* Chart */}
        <div style={{ flex: 1, minHeight: 0 }}>
          <TVChartContainer
            symbol={selectedSymbol}
            timeframe="OneHour"
            onChartReady={(chart) => {
              chartRef.current = chart;
              // Sync the React state when user changes symbol via TV's own search bar
              try {
                chart.onSymbolChanged().subscribe(null, () => {
                  try {
                    const newSym = chart.symbol();
                    if (newSym && newSym !== selectedSymbol) handleSelectSymbol(newSym);
                  } catch {}
                });
              } catch {}
            }}
          />
        </div>
      </div>

      {/* Right: 3 stacked panels */}
      <div style={{ width: 320, borderLeft: '1px solid #ddd', overflowY: 'auto', background: '#fff' }}>
        <CollapsibleSection title={`Active trades · ${selectedSymbol}`} defaultOpen={true}>
          <ActiveTradesPanel symbol={selectedSymbol} onSelectSymbol={handleSelectSymbol} refreshTick={refreshTick} />
        </CollapsibleSection>
        <CollapsibleSection title={`AI Watch trades · ${selectedSymbol}`} defaultOpen={true}>
          <WatchTradesPanel symbol={selectedSymbol} onSelectSymbol={handleSelectSymbol} refreshTick={refreshTick} />
        </CollapsibleSection>
        <CollapsibleSection title={`Simulated trades · ${selectedSymbol}`} defaultOpen={false}>
          <SimulatedTradesPanel
            symbol={selectedSymbol}
            onSelectSymbol={handleSelectSymbol}
            onSelectTrade={handleSelectSimTrade}
          />
        </CollapsibleSection>
        <CollapsibleSection title={`Annotations & thesis · ${selectedSymbol}`} defaultOpen={true}>
          <AnnotationsPanel symbol={selectedSymbol} tabUuid={tabId} interval="OneHour" />
        </CollapsibleSection>
      </div>

      {/* Toast */}
      {toast && (
        <div
          onClick={() => setToast(null)}
          style={{
            position: 'fixed', top: 16, right: 16, zIndex: 9999,
            padding: '10px 16px', borderRadius: 4,
            background: toast.kind === 'success' ? '#2e7d32' : '#c62828',
            color: 'white', fontSize: 13, maxWidth: 420, cursor: 'pointer',
            boxShadow: '0 2px 8px rgba(0,0,0,0.2)',
          }}>{toast.msg}</div>
      )}
    </div>
  );
}
