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
import CopilotChartPanel from './CopilotChartPanel';
import CopilotSettingsModal from './CopilotSettingsModal';
import type { CopilotHypothesis } from './copilotTypes';
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

// TradingView types (loaded globally via script tag)
declare const TradingView: any;


export default function TVChartApp() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const widgetRef = useRef<any>(null);
  const widgetReadyRef = useRef(false);
  const [isAnalysisPanelOpen, setIsAnalysisPanelOpen] = useState(false);
  const [isAiOverlayOpen, setIsAiOverlayOpen] = useState(false);
  const [isCopilotPanelOpen, setIsCopilotPanelOpen] = useState(false);
  const [showCopilotSettings, setShowCopilotSettings] = useState(false);
  const [showPromptBuilder, setShowPromptBuilder] = useState(false);
  const [layoutModalMode, setLayoutModalMode] = useState<'save' | 'load' | null>(null);
  const [copilotHypotheses, setCopilotHypotheses] = useState<CopilotHypothesis[]>([]);
  const hypothesisShapeIdsRef = useRef<any[]>([]);

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
  // F4 toggles AI overlay — function keys work even when chart iframe has focus.
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'F4' && !e.ctrlKey && !e.altKey && !e.metaKey) {
        e.preventDefault();
        setIsAiOverlayOpen(prev => !prev);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

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
      return { symbol: tab?.symbol || initSymbol, tabId: activeTabIdRef.current };
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

  // ─── Co-Pilot: draw yellow hypothesis labels on chart ────────────────────

  const handleHypothesesLoaded = useCallback((hypotheses: CopilotHypothesis[]) => {
    setCopilotHypotheses(hypotheses);
    if (!widgetReadyRef.current || !widgetRef.current) return;
    try {
      const chart = widgetRef.current.activeChart();

      // Remove previous hypothesis shapes
      hypothesisShapeIdsRef.current.forEach(id => {
        try { chart.removeEntity(id); } catch { /* ignore */ }
      });
      hypothesisShapeIdsRef.current = [];

      const visRange = chart.getVisibleRange();
      if (!visRange) return;

      const active = hypotheses.filter(h =>
        h.state === 'WATCHING' || h.state === 'BUILDING' || h.state === 'CONFIRMED'
      );

      // Draw a text label at the right side of the visible range for each active hypothesis.
      // Prices are spaced so labels don't overlap — we anchor to the chart's visible high.
      active.forEach((h, idx) => {
        try {
          // Attempt to extract a price from the anticipatory trade JSON
          let price: number | undefined;
          try {
            const tradeData = JSON.parse(h.anticipatoryTrade || '{}');
            const zone: string = tradeData.entry_zone ?? tradeData.entryZone ?? '';
            const match = zone.match(/[\d.]+/);
            if (match) price = Number(match[0]);
          } catch { /* ignore */ }

          // Fall back: space labels 2% apart from top of visible range
          if (!price) {
            const bars = chart.getVisibleRange();
            // We can't easily get the y-axis range, so just stack labels
            price = undefined;
          }

          const shapeId = chart.createShape(
            { time: visRange.to, price },
            {
              shape: 'text',
              lock: true,
              disableSelection: false,
              overrides: {
                text: `⚡ ${h.label}`,
                fontsize: 12,
                bold: true,
                color: '#FFD700',
                backgroundColor: 'rgba(26,35,126,0.75)',
                backgroundTransparency: 25,
              },
            },
          );
          if (shapeId) hypothesisShapeIdsRef.current.push(shapeId);
        } catch { /* TV shape API may not support all fields — fail silently */ }
      });
    } catch (e) {
      console.warn('Could not draw copilot annotations:', e);
    }
  }, []);

  // ─── Derived: active tab for display ─────────────────────────────────────

  const activeTab = tabs.find(t => t.id === activeTabId) || tabs[0];

  return (
    <div style={{ width: '100%', height: '100vh', display: 'flex', flexDirection: 'column', position: 'relative' }}>

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
        isCopilotOpen={isCopilotPanelOpen}
        isAnalysisOpen={isAnalysisPanelOpen}
        copilotCount={copilotHypotheses.filter(h => h.state === 'WATCHING' || h.state === 'BUILDING' || h.state === 'CONFIRMED').length}
        onToggleCopilot={() => setIsCopilotPanelOpen(prev => !prev)}
        onToggleAnalysis={() => setIsAnalysisPanelOpen(prev => !prev)}
        onCopilotSettings={() => setShowCopilotSettings(true)}
      />

      {/* Chart + Copilot panel row */}
      <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
        <div ref={chartContainerRef} style={{ flex: 1, minWidth: 0 }} />

        {/* Copilot inline panel — shrinks the chart rather than overlaying it */}
        <div style={{
          width: isCopilotPanelOpen ? 400 : 0,
          overflow: 'hidden',
          transition: 'width 0.3s ease',
          flexShrink: 0,
          borderLeft: isCopilotPanelOpen ? '1px solid #e0e0e0' : 'none',
        }}>
          <CopilotChartPanel
            open={isCopilotPanelOpen}
            onClose={() => setIsCopilotPanelOpen(false)}
            symbol={activeTab?.symbol || defaultSymbol}
            timeframe={activeTab?.timeframe || rawTimeframe}
            layoutId={Number(localStorage.getItem('lastLayoutId')) || null}
            getChartState={getChartState}
            onHypothesesLoaded={handleHypothesesLoaded}
          />
        </div>
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


      {/* Yellow hypothesis annotation badges — float over chart at bottom-right */}
      {copilotHypotheses.filter(h => h.state === 'WATCHING' || h.state === 'BUILDING' || h.state === 'CONFIRMED').length > 0 && (
        <div style={{
          position: 'fixed',
          bottom: 80,
          right: 20,
          zIndex: 9996,
          display: 'flex',
          flexDirection: 'column',
          gap: 4,
          alignItems: 'flex-end',
        }}>
          {copilotHypotheses
            .filter(h => h.state === 'WATCHING' || h.state === 'BUILDING' || h.state === 'CONFIRMED')
            .map(h => (
              <div
                key={h.id}
                onClick={() => setIsCopilotPanelOpen(true)}
                style={{
                  background: '#1a237e',
                  color: '#FFD700',
                  border: '1px solid #FFD700',
                  borderRadius: 6,
                  padding: '4px 10px',
                  fontSize: 11,
                  fontWeight: 700,
                  cursor: 'pointer',
                  boxShadow: '0 2px 8px rgba(0,0,0,0.3)',
                  whiteSpace: 'nowrap',
                  letterSpacing: 0.3,
                }}
                title={`${h.pattern} · ${h.direction} · Click to open Co-Pilot`}
              >
                ⚡ {h.label}
              </div>
            ))}
        </div>
      )}


      {/* Co-Pilot settings modal */}
      {showCopilotSettings && (
        <CopilotSettingsModal onClose={() => setShowCopilotSettings(false)} />
      )}

      {/* AI Chat Overlay — hidden when Prompt Builder is open */}
      {!showPromptBuilder && (
        <AIChatOverlay
          open={isAiOverlayOpen}
          onToggle={() => setIsAiOverlayOpen(prev => !prev)}
          symbol={activeTab?.symbol || defaultSymbol}
          timeframe={activeTab?.timeframe || rawTimeframe}
          getChartState={getChartState}
          tabs={tabs}
          activeTabId={activeTabId}
          onOpenPromptBuilder={() => setShowPromptBuilder(true)}
          copilotHypotheses={copilotHypotheses}
          onCopilotAction={() => {
            const id = Number(localStorage.getItem('copilot_investigation_id')) || null;
            // Just refresh from the panel if open; annotations will update via onHypothesesLoaded
          }}
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
    </div>
  );
}
