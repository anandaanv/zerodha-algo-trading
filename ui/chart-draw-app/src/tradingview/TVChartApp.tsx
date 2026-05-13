import React, { useEffect, useRef, useState, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { useSearchParams, useNavigate } from 'react-router-dom';
import datafeed from './datafeed';
import TVMultiPanelChart from './TVMultiPanelChart';
import { mapTimeframeToInterval, intervalToPeriod } from './intervalUtils';
import { createSaveLoadAdapter, mapToObject } from './saveLoadAdapter';
import AnalysisPanel from './AnalysisPanel';
import ChartTabBar from './ChartTabBar';
import AIChatOverlay from './AIChatOverlay';
import PromptBuilderPage from './PromptBuilderPage';
import ElliottChartPanel from './ElliottChartPanel';
import WatchlistSidebar from './WatchlistSidebar';
import {
  WorkspaceTab,
  WorkspaceLayout,
  newTab,
  loadTabsFromStorage,
  saveTabsToStorage,
  loadActiveTabIdFromStorage,
  saveActiveTabToStorage,
  loadWorkspaceName,
  saveWorkspaceName,
  loadWorkspaceLayouts,
  saveWorkspaceLayouts,
  getLastLayoutIdForSymbol,
  setLastLayoutIdForSymbol,
  updateWorkspaceLayout,
} from './workspaceTypes';
import WorkspaceLayoutModal from './WorkspaceLayoutModal';
import ScanResultsDrawer from './ScanResultsDrawer';
import { getApiUrl } from '../config/api';
import { withAuth } from '../utils/apiHelper';

// TradingView types (loaded globally via script tag)
declare const TradingView: any;

type ScanResponse = {
  symbol: string;
  scanTf: string;
  range: { from: number; to: number };
  drawings: Array<{
    id: string;
    type: string;
    score: number;
    metrics: Record<string, unknown>;
  }>;
  summary: { totalDrawings: number; bestId: string; bestScore: number };
};


export default function TVChartApp() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const widgetRef = useRef<any>(null);
  const widgetReadyRef = useRef(false);
  const patternShapeIdsRef = useRef<any[]>([]);
  const lastAnalysisResultRef = useRef<any>(null);
  const selectedPatternIdxRef = useRef<number | null>(null);
  const [isAnalysisPanelOpen, setIsAnalysisPanelOpen] = useState(false);
  const [isElliottPanelOpen, setIsElliottPanelOpen] = useState(false);
  const [isWatchlistOpen, setIsWatchlistOpen] = useState(false);
  const [showPromptBuilder, setShowPromptBuilder] = useState(false);
  const [layoutModalMode, setLayoutModalMode] = useState<'save' | 'load' | null>(null);

  const chartRef = useRef<any>(null);
  const [scanTf, setScanTf] = useState('1h');
  const [useVisibleRange, setUseVisibleRange] = useState(true);
  const [scanFromTime, setScanFromTime] = useState('');
  const [scanToTime, setScanToTime] = useState('');
  const [scanResult, setScanResult] = useState<ScanResponse | null>(null);
  const [scanLoading, setScanLoading] = useState(false);
  const [scanError, setScanError] = useState<string | null>(null);
  const [isScanDrawerOpen, setIsScanDrawerOpen] = useState(false);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiToast, setAiToast] = useState<{ msg: string; kind: 'success' | 'error' } | null>(null);

  // Parse URL parameters for initial defaults only
  const urlSymbol = searchParams.get('script') || searchParams.get('symbol');
  const urlTimeframe = searchParams.get('timeframe') || searchParams.get('period');
  const savedSymbol = localStorage.getItem('lastSymbol');
  const savedTimeframe = localStorage.getItem('lastTimeframe');
  const defaultSymbol = urlSymbol || savedSymbol || 'TCS';
  const rawTimeframe = urlTimeframe || savedTimeframe || '1h';

  const [currentLayoutId, setCurrentLayoutId] = useState<string | null>(() =>
    getLastLayoutIdForSymbol(loadWorkspaceName(defaultSymbol))
  );

  // Multi-timeframe special case (legacy feature)
  const timeframes = rawTimeframe ? rawTimeframe.split(',').map(tf => tf.trim()) : [];
  if (timeframes.length > 1) {
    return <TVMultiPanelChart symbol={defaultSymbol} timeframes={timeframes} />;
  }

  // ─── Tab State ────────────────────────────────────────────────────────────

  const [tabs, setTabs] = useState<WorkspaceTab[]>(() =>
    loadTabsFromStorage(defaultSymbol, rawTimeframe)
  );
  const [activeTabId, setActiveTabId] = useState<string>(() =>
    loadActiveTabIdFromStorage(loadTabsFromStorage(defaultSymbol, rawTimeframe))
  );
  const [workspaceName, setWorkspaceName] = useState<string>(() =>
    loadWorkspaceName(defaultSymbol)
  );

  const handleWorkspaceNameChange = useCallback((name: string) => {
    setWorkspaceName(name);
    saveWorkspaceName(name);
    // Auto-apply last used layout is done via applyLayoutRef below
    applyLayoutRef.current(name);
  }, []);

  // Refs so widget callbacks always see latest state without stale closures
  const tabsRef = useRef<WorkspaceTab[]>(tabs);
  const activeTabIdRef = useRef<string>(activeTabId);

  // Keep refs in sync with state
  useEffect(() => { tabsRef.current = tabs; }, [tabs]);
  useEffect(() => { activeTabIdRef.current = activeTabId; }, [activeTabId]);

  // ─── Helper: update a tab in both state and ref immediately ───────────────

  const updateTabInState = useCallback((tabId: string, patch: Partial<WorkspaceTab>) => {
    setTabs(prev => {
      const updated = prev.map(t => t.id === tabId ? { ...t, ...patch } : t);
      tabsRef.current = updated;
      saveTabsToStorage(updated);
      return updated;
    });
  }, []);

  // ─── Helper: save current chart state into the active tab ─────────────────

  const saveCurrentTabState = useCallback(() => {
    if (!widgetReadyRef.current || !widgetRef.current) return;
    try {
      const chart = widgetRef.current.activeChart();
      const lineState = chart.getLineToolsState();
      const visRange = chart.getVisibleRange();
      const serialized = JSON.stringify(mapToObject(lineState));
      const currentTabId = activeTabIdRef.current;
      const updated = tabsRef.current.map(t =>
        t.id === currentTabId
          ? { ...t, drawingsState: serialized, visibleFrom: visRange?.from, visibleTo: visRange?.to }
          : t
      );
      tabsRef.current = updated;
      // Don't call setTabs here — we're in an event handler, avoid triggering renders mid-switch
    } catch (e) {
      console.error('Failed to save tab state:', e);
    }
  }, []);

  // ─── Helper: apply a tab's saved drawings to the current chart ────────────

  const applyTabDrawings = useCallback((chart: any, tab: WorkspaceTab) => {
    if (!tab.drawingsState) return;
    try {
      const parsed = JSON.parse(tab.drawingsState);
      const stateWithMaps = {
        sources: new Map(Object.entries(parsed.sources || {})),
        groups: new Map(Object.entries(parsed.groups || {})),
      };
      chart.applyLineToolsState(stateWithMaps);
    } catch (e) {
      console.error('Failed to apply tab drawings:', e);
    }
  }, []);

  // ─── Switch to a different tab ────────────────────────────────────────────

  const switchToTab = useCallback((newTabId: string) => {
    if (!widgetReadyRef.current || newTabId === activeTabIdRef.current) return;

    // 1. Save current tab's state into ref (not React state to avoid render)
    saveCurrentTabState();

    // 2. Update active tab tracking
    activeTabIdRef.current = newTabId;
    setActiveTabId(newTabId);
    saveActiveTabToStorage(newTabId);

    const chart = widgetRef.current.activeChart();
    const newTab = tabsRef.current.find(t => t.id === newTabId);
    if (!newTab) return;

    const newInterval = mapTimeframeToInterval(newTab.timeframe) || '60';
    const currentInterval = chart.resolution();

    // 3. Switch symbol → then interval (if different) → then apply drawings
    if (chart.symbol() !== newTab.symbol) {
      chart.setSymbol(newTab.symbol, () => {
        if (currentInterval !== newInterval) {
          chart.setResolution(newInterval, () => applyTabDrawings(chart, newTab));
        } else {
          applyTabDrawings(chart, newTab);
        }
      });
    } else if (currentInterval !== newInterval) {
      chart.setResolution(newInterval, () => applyTabDrawings(chart, newTab));
    } else {
      applyTabDrawings(chart, newTab);
    }
  }, [saveCurrentTabState, applyTabDrawings]);

  // ─── Apply a saved layout ────────────────────────────────────────────────

  const applyLayout = useCallback((layout: WorkspaceLayout, targetSymbol: string) => {
    const newTabs: WorkspaceTab[] = layout.tabs.map(t => ({
      ...newTab(
        layout.scope === 'ALL' ? targetSymbol : t.symbol,
        t.timeframe,
      ),
      label: t.label,
    }));
    tabsRef.current = newTabs;
    setTabs(newTabs);
    saveTabsToStorage(newTabs);
    setLastLayoutIdForSymbol(targetSymbol, layout.id);
    setCurrentLayoutId(layout.id);
    setTimeout(() => switchToTab(newTabs[0].id), 0);
  }, [switchToTab]);

  // ─── Switch all tabs to a new workspace symbol ───────────────────────────

  const switchWorkspaceSymbol = useCallback((name: string) => {
    // Try last-used layout first
    const lastId = getLastLayoutIdForSymbol(name);
    if (lastId) {
      const layout = loadWorkspaceLayouts().find(l => l.id === lastId);
      if (layout) { applyLayout(layout, name); return; }
    }
    // No layout for this symbol — clear current layout so 💾 opens Save As dialog
    setCurrentLayoutId(null);
    // Replace all tab symbols with the new workspace symbol, keep timeframes/labels
    const updated = tabsRef.current.map(t => ({
      ...t,
      symbol: name,
      label: t.label === t.symbol ? name : t.label,  // update label if it mirrored the symbol
    }));
    tabsRef.current = updated;
    setTabs(updated);
    saveTabsToStorage(updated);
    // Switch to active tab to trigger chart symbol change
    const activeId = activeTabIdRef.current;
    activeTabIdRef.current = '';  // force switchToTab to treat it as a different tab
    setTimeout(() => switchToTab(activeId), 0);
  }, [applyLayout, switchToTab]);

  // Stable ref so handleWorkspaceNameChange (defined earlier) can call switchWorkspaceSymbol
  const applyLayoutRef = useRef<(symbol: string) => void>(() => {});
  applyLayoutRef.current = switchWorkspaceSymbol;

  // ─── Add a new tab ────────────────────────────────────────────────────────

  const addTab = useCallback(() => {
    const activeTab = tabsRef.current.find(t => t.id === activeTabIdRef.current);
    const tab = newTab(activeTab?.symbol || defaultSymbol, activeTab?.timeframe || rawTimeframe);
    const updated = [...tabsRef.current, tab];
    tabsRef.current = updated;
    setTabs(updated);
    saveTabsToStorage(updated);
    // Switch to it (this will also save current tab state)
    setTimeout(() => switchToTab(tab.id), 0);
  }, [switchToTab, defaultSymbol, rawTimeframe]);

  // ─── Close a tab ─────────────────────────────────────────────────────────

  const closeTab = useCallback((tabId: string) => {
    const current = tabsRef.current;
    if (current.length <= 1) return;

    const idx = current.findIndex(t => t.id === tabId);
    const updated = current.filter(t => t.id !== tabId);
    tabsRef.current = updated;
    setTabs(updated);
    saveTabsToStorage(updated);

    if (tabId === activeTabIdRef.current) {
      // Switch to the nearest tab
      const nextTab = updated[Math.min(idx, updated.length - 1)];
      if (nextTab) switchToTab(nextTab.id);
    }
  }, [switchToTab]);

  // ─── Rename a tab ────────────────────────────────────────────────────────

  const renameTab = useCallback((tabId: string, newLabel: string) => {
    updateTabInState(tabId, { label: newLabel });
  }, [updateTabInState]);

  // ─── Keyboard shortcut: backtick toggles AI overlay ─────────────────────
  // F4 toggle disabled — AI chat overlay replaced by Co-Pilot scan/reason
  // useEffect(() => {
  //   const onKeyDown = (e: KeyboardEvent) => {
  //     if (e.key === 'F4' && !e.ctrlKey && !e.altKey && !e.metaKey) {
  //       e.preventDefault();
  //       setIsAiOverlayOpen(prev => !prev);
  //     }
  //   };
  //   window.addEventListener('keydown', onKeyDown);
  //   return () => window.removeEventListener('keydown', onKeyDown);
  // }, []);

  // ─── Restore window focus after chart clicks so Ctrl+` keeps working ──────
  // Only steals focus back if nothing in the main document is actively focused
  // (e.g. a TradingView dialog rendered outside the iframe will have document.activeElement
  //  set to its own input — in that case we leave focus alone so TV typing works).
  useEffect(() => {
    const onBlur = () => {
      setTimeout(() => {
        const active = document.activeElement;
        if (active && active !== document.body && active !== document.documentElement) return;
        window.focus();
      }, 100);
    };
    window.addEventListener('blur', onBlur);
    return () => window.removeEventListener('blur', onBlur);
  }, []);

// ─── Widget Initialization (runs once) ────────────────────────────────────

  useEffect(() => {
    if (!chartContainerRef.current) return;

    const activeTab = tabsRef.current.find(t => t.id === activeTabIdRef.current)
      || tabsRef.current[0];
    const initSymbol = activeTab?.symbol || defaultSymbol;
    const initInterval = mapTimeframeToInterval(activeTab?.timeframe || rawTimeframe) || '60';

    const saveLoadAdapter = createSaveLoadAdapter(() => {
      const tab = tabsRef.current.find(t => t.id === activeTabIdRef.current);
      return {
        symbol: tab?.symbol || initSymbol,
        tabId: activeTabIdRef.current,
        timeframe: tab?.timeframe,
      };
    });

    const widgetOptions = {
      symbol: initSymbol,
      datafeed: datafeed,
      interval: initInterval,
      container: chartContainerRef.current,
      library_path: '/charting_library/charting_library/',
      locale: 'en',
      disabled_features: [],
      enabled_features: ['saveload_separate_drawings_storage', 'items_favoriting', 'library_custom_no_powered_branding'],
      custom_css_url: '/chart-custom.css',
      save_load_adapter: saveLoadAdapter,
      auto_save_delay: 5,
      load_last_chart: true,
      fullscreen: false,
      autosize: true,
      theme: 'light',
      timezone: 'Asia/Kolkata',
      debug: false,
    };

    const tvWidget = new TradingView.widget(widgetOptions);
    widgetRef.current = tvWidget;

    tvWidget.onChartReady(() => {
      widgetReadyRef.current = true;
      console.log('TradingView Chart is ready');

      const chart = tvWidget.activeChart();
      chartRef.current = chart;
      let saveTimeout: number | null = null;

      const triggerDrawingSave = async () => {
        if (saveTimeout) clearTimeout(saveTimeout);
        saveTimeout = window.setTimeout(async () => {
          try {
            const state = chart.getLineToolsState();
            await saveLoadAdapter.saveLineToolsAndGroups(undefined, chart.id(), state);
            // Also update in-memory tab state
            const serialized = JSON.stringify(mapToObject(state));
            const visRange = chart.getVisibleRange();
            const currentTabId = activeTabIdRef.current;
            tabsRef.current = tabsRef.current.map(t =>
              t.id === currentTabId
                ? { ...t, drawingsState: serialized, visibleFrom: visRange?.from, visibleTo: visRange?.to }
                : t
            );
          } catch (e) {
            console.error('Failed to auto-save drawings:', e);
          }
        }, 2000);
      };

      tvWidget.subscribe('drawing_event', triggerDrawingSave);

      // Track symbol changes (user typed in TV search bar)
      chart.onSymbolChanged().subscribe(null, () => {
        const newSymbol = chart.symbol();
        localStorage.setItem('lastSymbol', newSymbol);
        const currentTabId = activeTabIdRef.current;
        const updated = tabsRef.current.map(t =>
          t.id === currentTabId
            ? { ...t, symbol: newSymbol, label: t.label === t.symbol ? newSymbol : t.label }
            : t
        );
        tabsRef.current = updated;
        setTabs([...updated]);
        saveTabsToStorage(updated);
        setSearchParams({ symbol: newSymbol, timeframe: rawTimeframe }, { replace: true });
      });

      // Track interval changes
      chart.onIntervalChanged().subscribe(null, () => {
        const newInterval = chart.resolution();
        const newTimeframe = intervalToPeriod(newInterval);
        localStorage.setItem('lastTimeframe', newTimeframe);
        const currentTabId = activeTabIdRef.current;
        const updated = tabsRef.current.map(t =>
          t.id === currentTabId ? { ...t, timeframe: newTimeframe } : t
        );
        tabsRef.current = updated;
        setTabs([...updated]);
        saveTabsToStorage(updated);
      });
    });

    return () => {
      widgetReadyRef.current = false;
      if (widgetRef.current) {
        widgetRef.current.remove();
        widgetRef.current = null;
      }
    };
  }, []); // Run once — tab switching is handled imperatively

  // ─── drawAnalysisOnChart: render full Elliott analysis on the chart ──────

  const drawAnalysisOnChart = useCallback((result: any, selectedIdx: number | null = null) => {
    if (!widgetRef.current || !widgetReadyRef.current) return;
    try {
      const chart = widgetRef.current.activeChart();
      // Remove previous drawings
      patternShapeIdsRef.current.forEach(id => { try { chart.removeEntity(id); } catch {} });
      patternShapeIdsRef.current = [];
      lastAnalysisResultRef.current = result;
      selectedPatternIdxRef.current = selectedIdx;

      const pushId = (id: any) => { if (id) patternShapeIdsRef.current.push(id); };

      const toEpoch = (ts: string | null | undefined): number =>
        ts ? Math.floor(new Date(ts).getTime() / 1000) : 0;

      // ── helper: draw a trend_line between two points ──────────────────────
      const drawTrendLine = (
        p1: { time: number; price: number },
        p2: { time: number; price: number },
        color: string,
        linestyle: number,
        linewidth = 1
      ) => {
        try {
          const id = chart.createMultipointShape(
            [{ time: p1.time, price: p1.price }, { time: p2.time, price: p2.price }],
            { shape: 'trend_line', lock: false, overrides: { linecolor: color, linestyle, linewidth } }
          );
          pushId(id);
        } catch {}
      };

      // ── helper: draw polyline through array of points ─────────────────────
      const drawPolyline = (
        pts: { time: number; price: number }[],
        color: string,
        linestyle: number,
        linewidth = 1
      ) => {
        for (let i = 0; i < pts.length - 1; i++) {
          drawTrendLine(pts[i], pts[i + 1], color, linestyle, linewidth);
        }
      };

      // ── helper: draw horizontal line ──────────────────────────────────────
      const now = Math.floor(Date.now() / 1000);
      const drawHLine = (price: number, label: string, color: string, linestyle: number) => {
        try {
          const id = chart.createShape(
            { time: now, price },
            {
              shape: 'horizontal_line', text: label, lock: false,
              overrides: { linecolor: color, linestyle, linewidth: 1, showLabel: true, textcolor: color, horzLabelsAlign: 'right', vertLabelsAlign: 'bottom' },
            }
          );
          pushId(id);
        } catch {}
      };

      // ── helper: draw text label at a point ────────────────────────────────
      const drawLabel = (time: number, price: number, text: string, color: string) => {
        try {
          const id = chart.createShape(
            { time, price },
            {
              shape: 'text', text, lock: false,
              overrides: { color, fontsize: 11, bold: true },
            }
          );
          pushId(id);
        } catch {}
      };

      // ════════════════════════════════════════════════════════════════════════
      // LAYER 1: Pattern shapes
      // ════════════════════════════════════════════════════════════════════════
      const patterns: any[] = result?.waveAnalysis?.allPatterns ?? [];
      const BULLISH_TYPES = ['DOUBLE_BOTTOM','TRIPLE_BOTTOM','INVERTED_HEAD_AND_SHOULDERS','CUP_AND_HANDLE','ROUNDING_BOTTOM','ASCENDING_TRIANGLE','BULL_FLAG','BULL_PENNANT','FALLING_WEDGE','BROADENING_BOTTOM','LEADING_DIAGONAL'];

      patterns
        .filter((p: any) => p.status !== 'INVALIDATED')
        .forEach((p: any, idx: number) => {
          // If a pattern is selected, skip all others
          if (selectedIdx !== null && idx !== selectedIdx) return;
          const isBull = BULLISH_TYPES.includes(p.type);
          const baseColor = isBull ? '#2e7d32' : '#c62828';
          const isDashed = p.status !== 'CONFIRMED';
          const linestyle = isDashed ? 2 : 0;
          const tf = p.timeframe ? ` [${p.timeframe}]` : '';
          const patLabel = (p.type?.replace(/_/g, ' ') ?? '') + tf;

          // Build pivot points if available
          const timestamps: string[] = p.pivotTimestamps ?? [];
          const prices: number[] = p.pivotPrices ?? [];
          const hasPoints = timestamps.length >= 2 && prices.length === timestamps.length;

          if (hasPoints) {
            const pts = timestamps.map((ts: string, idx: number) => ({
              time: toEpoch(ts),
              price: prices[idx],
            })).filter(pt => pt.time > 0);

            if (pts.length >= 2) {
              // Determine drawing strategy by pattern type
              const type: string = p.type ?? '';
              const isTwoTrendline = ['ASCENDING_TRIANGLE','DESCENDING_TRIANGLE','RISING_WEDGE','FALLING_WEDGE',
                'BULL_FLAG','BEAR_FLAG','BULL_PENNANT','BEAR_PENNANT','BROADENING_TOP','BROADENING_BOTTOM'].includes(type);
              const isHnS = ['HEAD_AND_SHOULDERS','INVERTED_HEAD_AND_SHOULDERS'].includes(type);

              if (isTwoTrendline && pts.length >= 4) {
                // Split into even (highs/resistance side) and odd (lows/support side) indices
                const side1 = pts.filter((_: any, i: number) => i % 2 === 0);
                const side2 = pts.filter((_: any, i: number) => i % 2 === 1);
                if (side1.length >= 2) drawPolyline(side1, baseColor, linestyle, 1);
                if (side2.length >= 2) drawPolyline(side2, baseColor, linestyle, 1);
              } else if (isHnS && pts.length >= 5) {
                // Polyline through all pivots, plus slanted neckline between pts[1] and pts[3]
                drawPolyline(pts, baseColor, linestyle, 1);
                drawTrendLine(pts[1], pts[3], '#ff9800', 1, 1); // neckline
              } else {
                // Default: polyline through all pivots
                drawPolyline(pts, baseColor, linestyle, 1);
              }

              // Label at first pivot
              drawLabel(pts[0].time, pts[0].price, patLabel, baseColor);
            }
          } else {
            // Fallback: horizontal lines
            if (p.support != null)    drawHLine(p.support,    `${patLabel} support`,    baseColor, linestyle);
            if (p.resistance != null) drawHLine(p.resistance, `${patLabel} resistance`, baseColor, linestyle);
            if (p.neckline != null)   drawHLine(p.neckline,   `${patLabel} neckline`,   '#ff9800',  1);
          }

          // Always draw target as dashed horizontal line
          if (p.target != null) drawHLine(p.target, `${patLabel} target`, isBull ? '#1565c0' : '#880e4f', 2);
        });

      // ════════════════════════════════════════════════════════════════════════
      // LAYER 2: Elliott Wave counts
      // ════════════════════════════════════════════════════════════════════════
      const waveCounts: any[] = result?.waveAnalysis?.waveCounts ?? [];

      // Sort by total score desc, skip historical, take top 3
      const labelDisplay: Record<string, string> = {
        W1:'1', W2:'2', W3:'3', W4:'4', W5:'5',
        WA:'A', WB:'B', WC:'C', WD:'D', WE:'E',
        WX:'X', WY:'Y', WZ:'Z',
      };

      const waveColor = (wc: any, rank: number) => {
        if (rank === 0) {
          if (wc.waveType === 'LEADING_DIAGONAL' || wc.waveType === 'ENDING_DIAGONAL') return '#6a1b9a';
          if (wc.waveType?.includes('ZIGZAG') || wc.waveType?.includes('FLAT') || wc.waveType?.includes('TRIANGLE') || wc.waveType?.includes('COMPLEX') || wc.waveType?.includes('DOUBLE')) return '#ff8f00';
          return wc.bullish ? '#1565c0' : '#c62828';
        }
        return '#9e9e9e'; // secondary counts in grey
      };

      const totalScore = (wc: any) =>
        (wc.fibonacciScore ?? 0) + (wc.indicatorScore ?? 0) + (wc.crossTfScore ?? 0) +
        (wc.alternationScore ?? 0) + (wc.proportionalityBonus ?? 0);

      const activeWaveCounts = waveCounts
        .filter((wc: any) => !wc.historical && wc.pivots?.length >= 2)
        .sort((a: any, b: any) => totalScore(b) - totalScore(a))
        .slice(0, 3);

      activeWaveCounts.forEach((wc: any, rank: number) => {
        const color = waveColor(wc, rank);
        const linestyle = rank === 0 ? 0 : 2; // top count solid, others dashed
        const linewidth = rank === 0 ? 2 : 1;
        const tfSuffix = wc.primaryTimeframe ? ` [${wc.primaryTimeframe}]` : '';

        // Build points with wave labels
        const pivotToWave: Record<number, string> = wc.pivotToWave ?? {};
        const pts: { time: number; price: number; label: string }[] = (wc.pivots ?? [])
          .map((p: any) => ({
            time: toEpoch(p.timestamp),
            price: p.price,
            label: labelDisplay[pivotToWave[p.barIndex]] ?? '',
          }))
          .filter((pt: any) => pt.time > 0);

        if (pts.length < 2) return;

        // Draw wave polyline
        drawPolyline(pts, color, linestyle, linewidth);

        // Label each pivot
        pts.forEach(pt => {
          if (pt.label) {
            drawLabel(pt.time, pt.price, pt.label + tfSuffix, color);
          }
        });

        // Draw current-wave-in-progress as dashed line from last pivot to now
        if (wc.currentWaveInProgress && wc.currentWaveInProgress !== 'UNKNOWN') {
          const last = pts[pts.length - 1];
          const wipLabel = labelDisplay[wc.currentWaveInProgress] ?? wc.currentWaveInProgress;
          drawTrendLine(last, { time: now, price: last.price }, color, 2, 1);
          drawLabel(now, last.price, `→${wipLabel}${tfSuffix}`, color);
        }
      });

      // ════════════════════════════════════════════════════════════════════════
      // LAYER 3: Entry candidates + confluence zones
      // ════════════════════════════════════════════════════════════════════════
      const entryCandidates: any[] = result?.entryCandidates ?? [];
      entryCandidates.slice(0, 3).forEach((ec: any, i: number) => {
        const isBull = ec.direction === 'LONG' || ec.bullish;
        const tfLabel = ec.timeframe ? ` [${ec.timeframe}]` : '';
        if (ec.entryPrice != null) drawHLine(ec.entryPrice, `ENTRY${tfLabel}`, isBull ? '#00796b' : '#ad1457', 2);
        if (ec.stopLoss != null)   drawHLine(ec.stopLoss,   `SL${tfLabel}`,    '#ff6f00', 2);
        if (ec.target != null)     drawHLine(ec.target,     `T1${tfLabel}`,    isBull ? '#1565c0' : '#880e4f', 2);
      });

      const confluenceZones: any[] = result?.confluenceZones ?? [];
      confluenceZones.slice(0, 5).forEach((cz: any) => {
        if (cz.priceTop != null)    drawHLine(cz.priceTop,    `CONF ↑`, '#f57f17', 1);
        if (cz.priceBottom != null) drawHLine(cz.priceBottom, `CONF ↓`, '#f57f17', 1);
      });

    } catch (e) {
      console.error('Failed to draw analysis:', e);
    }
  }, []);

  const handlePatternSelect = useCallback((idx: number | null) => {
    selectedPatternIdxRef.current = idx;
    if (lastAnalysisResultRef.current) {
      drawAnalysisOnChart(lastAnalysisResultRef.current, idx);
    }
  }, [drawAnalysisOnChart]);

  // ─── getChartState: for AI analysis (active chart, live) ─────────────────

  const getChartState = (): string => {
    if (!widgetRef.current) throw new Error('Chart widget is not initialized');
    try {
      const chart = widgetRef.current.activeChart();
      const lineToolsState = chart.getLineToolsState();
      const visRange = chart.getVisibleRange();

      const stateObj: any = {
        drawings: [],
        symbol: chart.symbol(),
        resolution: chart.resolution(),
        timestamp: new Date().toISOString(),
        visibleFrom: visRange?.from,
        visibleTo: visRange?.to,
      };

      if (lineToolsState?.sources) {
        lineToolsState.sources.forEach((source: any) => {
          const inner = source.state ?? {};
          stateObj.drawings.push({
            id: source.id,
            type: inner.type,
            points: inner.points,
            properties: inner.state,
            zorder: inner.zorder,
          });
        });
      }

      console.log('Chart state captured:', stateObj.drawings.length, 'drawings');
      return JSON.stringify(stateObj);
    } catch (error) {
      console.error('Error getting chart state:', error);
      throw new Error('Failed to capture chart state');
    }
  };

  const resolutionToIntervalMap: Record<string, string> = {
    '1': 'OneMinute',
    '3': 'ThreeMinute',
    '5': 'FiveMinute',
    '15': 'FifteenMinute',
    '30': 'ThirtyMinute',
    '60': 'OneHour',
    '120': 'TwoHour',
    '1D': 'Day',
    '1W': 'Week',
  };

  const handleScan = useCallback(async () => {
    if (!chartRef.current) {
      setScanError('Chart not ready');
      return;
    }

    try {
      setScanLoading(true);
      setScanError(null);

      const chart = chartRef.current;
      const bareSymbol = chart.symbol();
      const activeTabId = activeTabIdRef.current;
      const scopedSymbol = `${activeTabId}:${bareSymbol}`;
      const currentResolution = chart.resolution();
      const drawingsTf = resolutionToIntervalMap[currentResolution] || 'OneHour';
      const scanTfMapped = resolutionToIntervalMap[scanTf] || 'OneHour';

      let fromSec: number;
      let toSec: number;

      if (useVisibleRange) {
        const visRange = chart.getVisibleRange();
        if (!visRange || visRange.from === undefined || visRange.to === undefined) {
          setScanError('Cannot read visible range');
          setScanLoading(false);
          return;
        }
        fromSec = Math.floor(visRange.from);
        toSec = Math.floor(visRange.to);
      } else {
        if (!scanFromTime || !scanToTime) {
          setScanError('Please specify From and To times');
          setScanLoading(false);
          return;
        }
        fromSec = Math.floor(new Date(scanFromTime).getTime() / 1000);
        toSec = Math.floor(new Date(scanToTime).getTime() / 1000);
      }

      const res = await fetch(
        getApiUrl('/api/drawing-scan/run').toString(),
        {
          ...withAuth(),
          method: 'POST',
          headers: { ...withAuth().headers, 'Content-Type': 'application/json' },
          body: JSON.stringify({
            symbol: scopedSymbol,
            drawingsTf,
            scanTf: scanTfMapped,
            fromSec,
            toSec,
          }),
        }
      );

      if (!res.ok) {
        setScanError(`HTTP ${res.status}: ${res.statusText}`);
        setScanLoading(false);
        return;
      }

      const data: ScanResponse = await res.json();
      setScanResult(data);
      setIsScanDrawerOpen(true);
      setScanLoading(false);
    } catch (err: any) {
      setScanError(err?.message || 'Failed to scan');
      setScanLoading(false);
    }
  }, [scanTf, useVisibleRange, scanFromTime, scanToTime]);

  const handleAiLevels = useCallback(async () => {
    if (!chartRef.current) {
      setAiToast({ msg: 'Chart not ready', kind: 'error' });
      return;
    }
    try {
      setAiLoading(true);
      const chart = chartRef.current;
      const symbol = chart.symbol();
      const resolution = chart.resolution();
      const timeframe = resolutionToIntervalMap[resolution] || 'OneHour';
      const tabId = activeTabIdRef.current;

      const res = await fetch(
        getApiUrl('/api/ai-levels/run').toString(),
        {
          ...withAuth(),
          method: 'POST',
          headers: { ...withAuth().headers, 'Content-Type': 'application/json' },
          body: JSON.stringify({ symbol, tabId, layoutId: 1, timeframe }),
        }
      );

      if (!res.ok) {
        const errBody = await res.text();
        throw new Error(`HTTP ${res.status}: ${errBody.substring(0, 200)}`);
      }

      const data: any = await res.json();
      const merged = data.lines_merged_count ?? data.linesMergedCount ?? 0;
      const suppressed = data.lines_suppressed_count ?? data.linesSuppressedCount ?? 0;
      let msg = `AI added ${merged} line${merged !== 1 ? 's' : ''}`;
      if (suppressed > 0) msg += ` (${suppressed} suppressed — previously deleted)`;
      setAiToast({ msg, kind: 'success' });

      // Refresh chart drawings by resetting data
      try {
        if (widgetRef.current && widgetRef.current.activeChart) {
          widgetRef.current.activeChart().resetData();
        }
      } catch (e) {
        console.warn('Failed to refresh chart drawings after AI levels:', e);
      }
    } catch (e: any) {
      setAiToast({ msg: e?.message || 'Failed to fetch AI levels', kind: 'error' });
    } finally {
      setAiLoading(false);
    }
  }, []);

  // Auto-dismiss aiToast after 5 seconds
  useEffect(() => {
    if (aiToast) {
      const timer = setTimeout(() => setAiToast(null), 5000);
      return () => clearTimeout(timer);
    }
  }, [aiToast]);

  // ─── Derived: active tab for display ─────────────────────────────────────

  const activeTab = tabs.find(t => t.id === activeTabId) || tabs[0];

  return (
    <div style={{ width: '100%', height: '100vh', display: 'flex', flexDirection: 'column', position: 'relative' }}>

      {/* Drawing Scan Toolbar */}
      <div style={{ padding: '8px 12px', backgroundColor: '#f5f5f5', borderBottom: '1px solid #e0e0e0', display: 'flex', gap: '12px', alignItems: 'center', flexWrap: 'wrap' }}>
        <label style={{ fontSize: '13px', display: 'flex', alignItems: 'center', gap: '6px' }}>
          <input
            type="checkbox"
            checked={useVisibleRange}
            onChange={(e) => setUseVisibleRange(e.target.checked)}
          />
          Use visible range
        </label>

        {!useVisibleRange && (
          <>
            <input
              type="datetime-local"
              value={scanFromTime}
              onChange={(e) => setScanFromTime(e.target.value)}
              style={{ fontSize: '12px', padding: '4px 6px' }}
            />
            <input
              type="datetime-local"
              value={scanToTime}
              onChange={(e) => setScanToTime(e.target.value)}
              style={{ fontSize: '12px', padding: '4px 6px' }}
            />
          </>
        )}

        <select
          value={scanTf}
          onChange={(e) => setScanTf(e.target.value)}
          style={{ fontSize: '12px', padding: '4px 6px' }}
        >
          <option value="1">1m</option>
          <option value="3">3m</option>
          <option value="5">5m</option>
          <option value="15">15m</option>
          <option value="30">30m</option>
          <option value="60">1h</option>
          <option value="120">2h</option>
          <option value="1D">1D</option>
          <option value="1W">1W</option>
        </select>

        <button
          onClick={handleScan}
          disabled={scanLoading}
          style={{
            fontSize: '12px',
            padding: '6px 12px',
            backgroundColor: scanLoading ? '#ccc' : '#1565c0',
            color: 'white',
            border: 'none',
            borderRadius: '4px',
            cursor: scanLoading ? 'not-allowed' : 'pointer',
          }}
        >
          {scanLoading ? 'Scanning...' : 'Scan'}
        </button>

        {isScanDrawerOpen && (
          <button
            onClick={() => setIsScanDrawerOpen(false)}
            style={{
              fontSize: '12px',
              padding: '6px 12px',
              backgroundColor: '#e0e0e0',
              border: 'none',
              borderRadius: '4px',
              cursor: 'pointer',
            }}
          >
            Hide Results
          </button>
        )}

        <button
          onClick={handleAiLevels}
          disabled={aiLoading}
          style={{
            fontSize: '12px',
            padding: '6px 12px',
            backgroundColor: aiLoading ? '#ccc' : '#7E57C2',
            color: 'white',
            border: 'none',
            borderRadius: '4px',
            cursor: aiLoading ? 'not-allowed' : 'pointer',
          }}
        >
          {aiLoading ? 'Drawing…' : '✨ AI Levels'}
        </button>
      </div>

      {/* Tab Bar */}
      <ChartTabBar
        tabs={tabs}
        activeTabId={activeTabId}
        workspaceName={workspaceName}
        onWorkspaceNameChange={handleWorkspaceNameChange}
        onSwitch={switchToTab}
        onAdd={addTab}
        onClose={closeTab}
        onRename={renameTab}
        onSaveLayout={() => {
          const layoutTabs = tabs.map(t => ({ label: t.label, symbol: t.symbol, timeframe: t.timeframe }));
          if (currentLayoutId) {
            // Overwrite existing layout in place — no dialog
            updateWorkspaceLayout(currentLayoutId, layoutTabs);
          } else {
            // No layout saved yet — auto-create silently using workspace name
            const layout = {
              id: crypto.randomUUID(),
              name: workspaceName || 'Default',
              scope: 'ALL' as const,
              tabs: layoutTabs,
              createdAt: Date.now(),
            };
            saveWorkspaceLayouts([...loadWorkspaceLayouts(), layout]);
            setLastLayoutIdForSymbol(workspaceName, layout.id);
            setCurrentLayoutId(layout.id);
          }
        }}
        onSaveAsLayout={() => setLayoutModalMode('save')}
        onLoadLayout={() => setLayoutModalMode('load')}
        isCopilotOpen={isElliottPanelOpen}
        isAnalysisOpen={isAnalysisPanelOpen}
        onToggleCopilot={() => setIsElliottPanelOpen(prev => !prev)}
        onToggleAnalysis={() => setIsAnalysisPanelOpen(prev => !prev)}
        onToggleWatchlist={() => setIsWatchlistOpen(prev => !prev)}
        isWatchlistOpen={isWatchlistOpen}
      />

      {/* Chart + Copilot panel row */}
      <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
        <WatchlistSidebar
          open={isWatchlistOpen}
          onClose={() => setIsWatchlistOpen(false)}
          onSymbolSelect={(sym) => {
            const existingTab = tabs.find(t => t.symbol === sym);
            if (existingTab) {
              switchToTab(existingTab.id);
            } else {
              const newTabItem = newTab(sym, activeTab?.timeframe || rawTimeframe);
              const updated = [...tabsRef.current, newTabItem];
              tabsRef.current = updated;
              setTabs(updated);
              saveTabsToStorage(updated);
              setTimeout(() => switchToTab(newTabItem.id), 0);
            }
          }}
        />
        <div ref={chartContainerRef} style={{ flex: 1, minWidth: 0 }} />

        {/* Elliott panel — shrinks chart rather than overlaying it */}
        <div style={{
          width: isElliottPanelOpen ? 400 : 0,
          overflow: 'hidden',
          transition: 'width 0.3s ease',
          flexShrink: 0,
          borderLeft: isElliottPanelOpen ? '1px solid #e0e0e0' : 'none',
        }}>
          <ElliottChartPanel
            open={isElliottPanelOpen}
            onClose={() => setIsElliottPanelOpen(false)}
            symbol={activeTab?.symbol || defaultSymbol}
            timeframes={[...new Set(
              tabs
                .filter(t => t.symbol === (activeTab?.symbol || defaultSymbol))
                .map(t => t.timeframe)
            )]}
            layoutId={Number(localStorage.getItem('lastLayoutId')) || null}
            getChartState={getChartState}
            onAnalysisReady={drawAnalysisOnChart}
            onPatternSelect={handlePatternSelect}
            selectedPatternIdx={selectedPatternIdxRef.current}
          />
        </div>

        {/* Scan Results Drawer */}
        <ScanResultsDrawer
          open={isScanDrawerOpen}
          onClose={() => setIsScanDrawerOpen(false)}
          scanResult={scanResult}
          loading={scanLoading}
          error={scanError}
          symbol={activeTab?.symbol || defaultSymbol}
          scanTf={scanTf}
        />
      </div>

      {/* Workspace Layout Modal */}
      {layoutModalMode && (
        <WorkspaceLayoutModal
          mode={layoutModalMode}
          workspaceName={workspaceName}
          currentTabs={tabs}
          onSave={layout => {
            setLastLayoutIdForSymbol(workspaceName, layout.id);
            setCurrentLayoutId(layout.id);
            setLayoutModalMode(null);
          }}
          onLoad={layout => {
            applyLayout(layout, workspaceName);
            setLayoutModalMode(null);
          }}
          onClose={() => setLayoutModalMode(null)}
        />
      )}


      {/* Prompt Builder — rendered via portal into document.body so it
          sits above the TradingView iframe stacking context */}
      {showPromptBuilder && createPortal(
        <div style={{ position: 'fixed', inset: 0, zIndex: 99999 }}>
          <PromptBuilderPage onClose={() => setShowPromptBuilder(false)} />
        </div>,
        document.body
      )}

      {/* Analysis Panel (sidebar — fundamentals, news, snapshots) */}
      <AnalysisPanel
        open={isAnalysisPanelOpen}
        symbol={activeTab?.symbol || defaultSymbol}
        timeframe={activeTab?.timeframe || rawTimeframe}
        onClose={() => setIsAnalysisPanelOpen(false)}
        getChartState={getChartState}
        tabs={tabs}
        activeTabId={activeTabId}
      />

      {/* AI Levels Toast Notification */}
      {aiToast && (
        <div
          style={{
            position: 'fixed',
            top: 16,
            right: 16,
            zIndex: 9999,
            padding: '10px 16px',
            borderRadius: 4,
            background: aiToast.kind === 'success' ? '#2e7d32' : '#c62828',
            color: 'white',
            fontSize: 13,
            maxWidth: 360,
            cursor: 'pointer',
          }}
          onClick={() => setAiToast(null)}
        >
          {aiToast.msg}
        </div>
      )}
    </div>
  );
}
