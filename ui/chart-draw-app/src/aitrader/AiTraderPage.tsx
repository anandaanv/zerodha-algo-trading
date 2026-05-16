import { useRef, useState, useCallback } from 'react';
import TVChartContainer from '../tradingview/TVChartContainer';
import CollapsibleSection from './CollapsibleSection';
import ActiveTradesPanel from './ActiveTradesPanel';
import WatchTradesPanel from './WatchTradesPanel';
import SimulatedTradesPanel from './SimulatedTradesPanel';
import WatchlistPanel from './WatchlistPanel';
import AnnotationsPanel from './annotations/AnnotationsPanel';
import DrawingNoteInput from './annotations/DrawingNoteInput';
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

// Small chevron tab that overlays the inner edge of a side panel.
// side='right' → tab on the right edge (used by the left watchlist panel).
// side='left'  → tab on the left edge (used by the right stack panel).
function collapseTabStyle(side: 'left' | 'right'): React.CSSProperties {
  return {
    position: 'absolute',
    top: 8,
    [side]: 2,
    width: 18, height: 22,
    background: '#fff',
    border: '1px solid #ddd',
    borderRadius: 3,
    cursor: 'pointer',
    fontSize: 12,
    color: '#666',
    padding: 0,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 5,
  };
}

function isoToEpochSec(t: any): number {
  if (typeof t === 'number') return t > 1e12 ? Math.floor(t / 1000) : t;
  if (typeof t === 'string') {
    const ms = Date.parse(t);
    return isNaN(ms) ? 0 : Math.floor(ms / 1000);
  }
  return 0;
}

/**
 * Programmatically trigger TradingView's "Go To Date" dialog (Alt+G) and click
 * the target day in the calendar picker.
 *
 * The dialog renders a calendar (not a date input):
 *   button[aria-label^="Switch to months"]   ← header, text = "May 2026"
 *   button[aria-label^="Previous month"]     ← navigates back one month
 *   button[aria-label^="Next month"]         ← navigates forward one month
 *   button[data-day="2024-03-15"]            ← cell for a specific day
 *
 * TV renders the calendar into the parent document body via a portal, but we search
 * both the parent document and any same-origin iframes to be defensive.
 *
 * @param chart   TradingView active chart from widget.activeChart()
 * @param dateYmd Target date as "YYYY-MM-DD"
 */
function triggerGoToDate(chart: any, dateYmd: string): void {
  try {
    chart.executeActionById?.('Chart.Dialogs.ShowGoToDate');
  } catch (e) {
    console.warn('[triggerGoToDate] executeActionById failed:', e);
    return;
  }

  const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June',
                  'July', 'August', 'September', 'October', 'November', 'December'];

  const parts = dateYmd.split('-').map(Number);
  if (parts.length !== 3 || parts.some(Number.isNaN)) {
    console.warn('[triggerGoToDate] bad date:', dateYmd);
    return;
  }
  const [yr, mo /* 1-based */, dy] = parts;
  const targetMonthIdx = mo - 1;

  // Collect parent document + any accessible iframe documents
  const collectDocs = (): Document[] => {
    const out: Document[] = [document];
    document.querySelectorAll<HTMLIFrameElement>('iframe').forEach(f => {
      try { if (f.contentDocument) out.push(f.contentDocument); } catch { /* cross-origin */ }
    });
    return out;
  };
  const findIn = <T extends Element>(selector: string): T | null => {
    for (const d of collectDocs()) {
      const el = d.querySelector<T>(selector);
      if (el) return el;
    }
    return null;
  };
  const parseHeader = (): { month: number; year: number } | null => {
    const btn = findIn<HTMLButtonElement>('button[aria-label^="Switch to months"]');
    if (!btn) return null;
    const m = (btn.textContent || '').trim().match(/^([A-Za-z]+)\s+(\d+)$/);
    if (!m) return null;
    const idx = MONTHS.indexOf(m[1]);
    return idx < 0 ? null : { month: idx, year: parseInt(m[2], 10) };
  };
  const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

  (async () => {
    // 1. Wait for the calendar to render
    let header: ReturnType<typeof parseHeader> = null;
    for (let i = 0; i < 30; i++) {
      header = parseHeader();
      if (header) break;
      await sleep(80);
    }
    if (!header) {
      console.warn('[triggerGoToDate] calendar header not found — dialog may not have opened');
      return;
    }

    // 2. Navigate prev/next month until header matches target year+month
    for (let safety = 0; safety < 360; safety++) {
      header = parseHeader();
      if (!header) break;
      if (header.year === yr && header.month === targetMonthIdx) break;
      const goBack = header.year > yr || (header.year === yr && header.month > targetMonthIdx);
      const btn = findIn<HTMLButtonElement>(
        goBack ? 'button[aria-label^="Previous month"]' : 'button[aria-label^="Next month"]'
      );
      if (!btn || btn.disabled) {
        console.warn('[triggerGoToDate] nav button missing/disabled at', header, 'targeting', { yr, targetMonthIdx });
        break;
      }
      btn.click();
      await sleep(30);
    }

    // 3. Click the target day. If disabled (weekend/holiday), pick the nearest
    //    enabled prior day within the same month.
    let dayBtn = findIn<HTMLButtonElement>(`button[data-day="${dateYmd}"]`);
    if (!dayBtn || dayBtn.disabled) {
      const alt = new Date(Date.UTC(yr, targetMonthIdx, dy));
      for (let i = 1; i < 10; i++) {
        alt.setUTCDate(alt.getUTCDate() - 1);
        if (alt.getUTCMonth() !== targetMonthIdx) break;
        const altYmd = alt.toISOString().substring(0, 10);
        const b = findIn<HTMLButtonElement>(`button[data-day="${altYmd}"]`);
        if (b && !b.disabled) { dayBtn = b; break; }
      }
    }

    if (dayBtn) {
      dayBtn.click();
      console.log('[triggerGoToDate] clicked day:', dayBtn.getAttribute('data-day'));
    } else {
      console.warn('[triggerGoToDate] no enabled day cell found near', dateYmd);
    }
  })();
}

export default function AiTraderPage() {
  // Persist the active symbol so refresh reloads what the trader was last looking at.
  // Falls back to WIPRO when nothing's stored (first visit).
  const [selectedSymbol, setSelectedSymbol] = useState<string>(() =>
    (localStorage.getItem('aitrader_symbol') || 'WIPRO').toUpperCase()
  );
  const [refreshTick, setRefreshTick] = useState<number>(0);
  const [aiLoading, setAiLoading] = useState(false);
  const [analyseLoading, setAnalyseLoading] = useState(false);
  const [toast, setToast] = useState<{msg: string; kind: 'success'|'error'} | null>(null);
  // Panel collapse state — persisted so the trader's layout survives refresh.
  const [leftCollapsed, setLeftCollapsed] = useState<boolean>(() =>
    localStorage.getItem('aitrader_left_collapsed') === '1'
  );
  const [rightCollapsed, setRightCollapsed] = useState<boolean>(() =>
    localStorage.getItem('aitrader_right_collapsed') === '1'
  );
  const toggleLeft = useCallback(() => {
    setLeftCollapsed(v => {
      const next = !v;
      localStorage.setItem('aitrader_left_collapsed', next ? '1' : '0');
      return next;
    });
  }, []);
  const toggleRight = useCallback(() => {
    setRightCollapsed(v => {
      const next = !v;
      localStorage.setItem('aitrader_right_collapsed', next ? '1' : '0');
      return next;
    });
  }, []);

  const chartRef = useRef<any>(null);
  const aiShapeIdsRef = useRef<any[]>([]);
  const simTradeShapeIdsRef = useRef<any[]>([]);
  // TV widget instance — used by DrawingNoteInput to subscribe to widget-level
  // drawing_event. Kept in React state so the input re-mounts/wires when widget
  // becomes ready.
  const [tvWidget, setTvWidget] = useState<any>(null);
  const [annotationsRefreshTick, setAnnotationsRefreshTick] = useState<number>(0);
  const tabId = getOrCreateTabId();

  // Single source of truth for symbol changes — bumps refreshTick so dependent panels re-fetch,
  // and persists to localStorage so refresh restores the same symbol.
  const handleSelectSymbol = useCallback((symbol: string) => {
    if (!symbol) return;
    const up = symbol.trim().toUpperCase();
    setSelectedSymbol(up);
    localStorage.setItem('aitrader_symbol', up);
    setRefreshTick(t => t + 1);
  }, []);

  const showToast = useCallback((msg: string, kind: 'success'|'error' = 'success') => {
    setToast({ msg, kind });
    setTimeout(() => setToast(null), 5000);
  }, []);

  // Pan the chart to a simulated trade and draw entry/SL/TP horizontals.
  // Strategy:
  //   1. Try chart.setVisibleRange (proper API). It may return a Promise — await it.
  //   2. Bars for the trade's date may not be loaded yet; subscribe to onDataLoaded
  //      and re-apply the range once data arrives (mirrors TVChartContainer's pattern).
  //   3. After a grace period, if the visible range is still far from the target,
  //      fall back to TradingView's Go To Date dialog (Alt+G) — open it via
  //      executeActionById('Chart.Dialogs.ShowGoToDate'), then pre-fill the date input
  //      and click Submit using the iframe's DOM (same-origin, so accessible).
  // No React state changes anywhere in this handler — the TVChartContainer widget
  // must NOT remount.
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

    if (entryEpoch <= 0) {
      showToast('Trade has no entry time', 'error');
      return;
    }

    const entryDateIso = new Date(entryEpoch * 1000).toISOString();
    console.log('[handleSelectSimTrade] trade:', {
      symbol, direction, entry, sl, target,
      entryISO: entryDateIso,
      exitISO: new Date(exitEpoch * 1000).toISOString(),
    });

    // If a stray different-symbol trade lands here, switch via TV API (no remount).
    if (symbol && symbol !== selectedSymbol) {
      try { chart.setSymbol?.(symbol, () => {}); } catch (e) { console.warn('setSymbol failed', e); }
    }

    // Preserve current zoom: keep the visible span, just slide the window so the
    // trade's entry is centered. setVisibleRange to a tighter window than the
    // current one would force TV to zoom in (resize candles), which the trader
    // doesn't want — they just want to scroll to the date.
    let span: number;
    try {
      const current = chart.getVisibleRange?.();
      span = current && current.to > current.from
        ? (current.to - current.from)
        : Math.max(86400 * 30, exitEpoch - entryEpoch);
    } catch {
      span = Math.max(86400 * 30, exitEpoch - entryEpoch);
    }
    const center = (entryEpoch + exitEpoch) / 2;
    const targetRange = { from: center - span / 2, to: center + span / 2 };

    // Helper: apply the range, await any Promise the SDK returns.
    const applyRange = async () => {
      try {
        const result = chart.setVisibleRange(targetRange);
        if (result && typeof result.then === 'function') await result;
        return true;
      } catch (e) {
        console.warn('[handleSelectSimTrade] setVisibleRange failed:', e);
        return false;
      }
    };

    // 1. First attempt
    await applyRange();

    // 2. Retry once after the datafeed reports fresh bars are loaded.
    try {
      const sub = chart.onDataLoaded?.();
      if (sub && typeof sub.subscribe === 'function') {
        let fired = false;
        sub.subscribe(null, () => {
          if (fired) return;
          fired = true;
          setTimeout(applyRange, 200);
        });
      }
    } catch (e) { /* ignore — onDataLoaded may not be supported */ }

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
          { time: entryEpoch, price },
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

    const when = entryDateIso.substring(0, 16).replace('T', ' ');
    showToast(`${symbol} ${direction} · ${when} · entry ${entry.toFixed(2)}`);

    // 3. After a grace period, if the visible range hasn't reached the target,
    //    fall back to the Go To Date dialog (Alt+G) and pre-fill it.
    setTimeout(() => {
      try {
        const current = chart.getVisibleRange?.();
        if (!current) return;
        const targetCenter = (targetRange.from + targetRange.to) / 2;
        const currentCenter = (current.from + current.to) / 2;
        const drift = Math.abs(targetCenter - currentCenter);
        // 30 days drift = setVisibleRange didn't take effect → fall back
        if (drift > 30 * 86400) {
          console.warn('[handleSelectSimTrade] visible range drifted by', drift, 's — opening Go To Date dialog');
          triggerGoToDate(chart, entryDateIso.substring(0, 10));
        }
      } catch (e) { /* ignore */ }
    }, 1500);
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
      {/* Left: Watchlist (collapsible) */}
      {!leftCollapsed && (
        <div style={{ width: 260, borderRight: '1px solid #ddd', overflowY: 'auto', background: '#fff', position: 'relative' }}>
          <button
            onClick={toggleLeft}
            title="Collapse watchlist"
            style={collapseTabStyle('right')}
          >‹</button>
          <WatchlistPanel
            onSelectSymbol={handleSelectSymbol}
            onBatchComplete={() => setRefreshTick(t => t + 1)}
          />
        </div>
      )}
      {leftCollapsed && (
        <button
          onClick={toggleLeft}
          title="Expand watchlist"
          style={{
            width: 18, borderRight: '1px solid #ddd', background: '#f5f5f5',
            cursor: 'pointer', border: 'none', borderLeft: 'none',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 14, color: '#666',
          }}
        >›</button>
      )}

      {/* Center: toolbar + chart */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', borderRight: rightCollapsed ? 'none' : '1px solid #ddd', background: '#fff' }}>
        {/* Toolbar — symbol is shown by TradingView's own header, so we only carry the AI buttons */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8,
          padding: '6px 12px', borderBottom: '1px solid #e0e0e0', background: '#fafafa',
          fontSize: 13,
        }}>
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
          {/* On-demand drawing note input — appears only when a drawing is selected on the chart */}
          <DrawingNoteInput
            widget={tvWidget}
            symbol={selectedSymbol}
            tabUuid={tabId}
            interval="OneHour"
            onSaved={() => setAnnotationsRefreshTick(t => t + 1)}
          />
        </div>
        {/* Chart */}
        <div style={{ flex: 1, minHeight: 0 }}>
          <TVChartContainer
            symbol={selectedSymbol}
            timeframe="OneHour"
            tabId={tabId}
            onWidgetReady={(w) => setTvWidget(w)}
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

      {/* Right: 3 stacked panels (collapsible) */}
      {rightCollapsed && (
        <button
          onClick={toggleRight}
          title="Expand panels"
          style={{
            width: 18, borderLeft: '1px solid #ddd', background: '#f5f5f5',
            cursor: 'pointer', border: 'none',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 14, color: '#666',
          }}
        >‹</button>
      )}
      {!rightCollapsed && (
      <div style={{ width: 320, borderLeft: '1px solid #ddd', overflowY: 'auto', background: '#fff', position: 'relative' }}>
        <button
          onClick={toggleRight}
          title="Collapse panels"
          style={collapseTabStyle('left')}
        >›</button>
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
        <CollapsibleSection title={`Annotations & journal · ${selectedSymbol}`} defaultOpen={true}>
          <AnnotationsPanel
            key={`ann-${annotationsRefreshTick}`}
            symbol={selectedSymbol}
            tabUuid={tabId}
            interval="OneHour"
          />
        </CollapsibleSection>
      </div>
      )}

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
