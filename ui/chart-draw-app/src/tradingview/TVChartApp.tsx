import React, { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import datafeed from './datafeed';
import TVMultiPanelChart from './TVMultiPanelChart';
import { mapTimeframeToInterval, intervalToPeriod } from './intervalUtils';
import { createSaveLoadAdapter } from './saveLoadAdapter';
import AnalysisPanel from './AnalysisPanel';

// TradingView types (loaded globally via script tag)
declare const TradingView: any;

export default function TVChartApp() {
  const [searchParams, setSearchParams] = useSearchParams();
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const widgetRef = useRef<any>(null);
  const [isAnalysisPanelOpen, setIsAnalysisPanelOpen] = useState(false);

  // Storage keys
  const STORAGE_SYMBOL_KEY = 'lastSymbol';
  const STORAGE_TIMEFRAME_KEY = 'lastTimeframe';

  // Parse URL parameters
  const urlSymbol = searchParams.get('script') || searchParams.get('symbol');
  const urlTimeframe = searchParams.get('timeframe') || searchParams.get('period');

  // Get from localStorage if not in URL
  const savedSymbol = localStorage.getItem(STORAGE_SYMBOL_KEY);
  const savedTimeframe = localStorage.getItem(STORAGE_TIMEFRAME_KEY);

  // Determine actual values (URL > localStorage > defaults)
  const defaultSymbol = urlSymbol || savedSymbol || 'TCS';
  const rawTimeframe = urlTimeframe || savedTimeframe || '1h';

  // Check if multiple timeframes are requested (comma-separated)
  const timeframes = rawTimeframe ? rawTimeframe.split(',').map((tf) => tf.trim()) : [];

  // If multiple timeframes, render multi-panel view
  if (timeframes.length > 1) {
    return <TVMultiPanelChart symbol={defaultSymbol} timeframes={timeframes} />;
  }

  const defaultInterval = mapTimeframeToInterval(rawTimeframe) || '60';

  // Save to localStorage and update URL if needed
  useEffect(() => {
    localStorage.setItem(STORAGE_SYMBOL_KEY, defaultSymbol);
    localStorage.setItem(STORAGE_TIMEFRAME_KEY, rawTimeframe);

    // Update URL if parameters are missing
    if (!urlSymbol || !urlTimeframe) {
      setSearchParams({ symbol: defaultSymbol, timeframe: rawTimeframe }, { replace: true });
    }
  }, [defaultSymbol, rawTimeframe, urlSymbol, urlTimeframe, setSearchParams]);

  useEffect(() => {
    if (!chartContainerRef.current) return;

    const saveLoadAdapter = createSaveLoadAdapter(defaultSymbol, defaultInterval);

    const widgetOptions = {
      symbol: defaultSymbol,
      datafeed: datafeed,
      interval: defaultInterval,
      container: chartContainerRef.current,
      library_path: '/charting_library/charting_library/',
      locale: 'en',
      disabled_features: ['use_localstorage_for_settings'],
      enabled_features: ['saveload_separate_drawings_storage'],
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
      console.log('TradingView Chart is ready');

      const chart = tvWidget.activeChart();
      let saveTimeout: number | null = null;

      const saveDrawings = async () => {
        if (saveTimeout) clearTimeout(saveTimeout);
        saveTimeout = window.setTimeout(async () => {
          try {
            // Get current drawings state from chart (contains Maps)
            const state = chart.getLineToolsState();
            // Save via adapter (adapter handles Map-to-object conversion)
            await saveLoadAdapter.saveLineToolsAndGroups(undefined, chart.id(), state);
          } catch (e) {
            console.error('Failed to auto-save drawings:', e);
          }
        }, 2000); // Debounce 2 seconds
      };

      // Subscribe to drawing events to trigger auto-save
      tvWidget.subscribe('drawing_event', () => {
        saveDrawings();
      });

      // Subscribe to auto-save events for chart layout (indicators, settings)
      tvWidget.subscribe('onAutoSaveNeeded', () => {
        // TradingView will automatically call saveChart via save_load_adapter
      });

      // Subscribe to symbol changes to update localStorage and URL
      chart.onSymbolChanged().subscribe(null, () => {
        const newSymbol = chart.symbol();
        localStorage.setItem(STORAGE_SYMBOL_KEY, newSymbol);
        setSearchParams({ symbol: newSymbol, timeframe: rawTimeframe }, { replace: true });
      });

      // Subscribe to interval changes to update localStorage and URL
      chart.onIntervalChanged().subscribe(null, () => {
        const newInterval = chart.resolution();
        // Convert TradingView resolution back to timeframe format
        const newTimeframe = intervalToPeriod(newInterval);
        localStorage.setItem(STORAGE_TIMEFRAME_KEY, newTimeframe);
        setSearchParams({ symbol: defaultSymbol, timeframe: newTimeframe }, { replace: true });
      });
    });

    return () => {
      if (widgetRef.current) {
        widgetRef.current.remove();
        widgetRef.current = null;
      }
    };
  }, [defaultSymbol, defaultInterval]);

  // Function to get current chart state as JSON
  const getChartState = (): string => {
    if (!widgetRef.current) {
      throw new Error('Chart widget is not initialized');
    }

    try {
      const chart = widgetRef.current.activeChart();
      const lineToolsState = chart.getLineToolsState();

      // Convert the state to a plain object (handle Maps)
      const stateObj: any = {
        drawings: [],
        symbol: chart.symbol(),
        resolution: chart.resolution(),
        timestamp: new Date().toISOString()
      };

      // Each source is { id, state: { type, state: {...visualProps}, points: [...], zorder } }
      if (lineToolsState && lineToolsState.sources) {
        lineToolsState.sources.forEach((source: any) => {
          const inner = source.state ?? {};
          stateObj.drawings.push({
            id: source.id,
            type: inner.type,                 // e.g. "LineToolTrendLine"
            points: inner.points,             // [{ time_t, offset, price, interval }]
            properties: inner.state,          // { linecolor, linewidth, text, title, ... }
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

  return (
    <div style={{ width: '100%', height: '100vh', position: 'relative' }}>
      <div ref={chartContainerRef} style={{ width: '100%', height: '100%' }} />

      {/* Floating Action Buttons */}
      <div style={{
        position: 'fixed',
        top: '20px',
        right: isAnalysisPanelOpen ? '470px' : '20px',
        display: 'flex',
        gap: '12px',
        zIndex: 9999,
        transition: 'all 0.3s ease',
      }}>
        {/* Analyse Button */}
        <button
          onClick={() => setIsAnalysisPanelOpen(!isAnalysisPanelOpen)}
          style={{
            width: '120px',
            height: '40px',
            backgroundColor: '#1976d2',
            color: 'white',
            border: 'none',
            borderRadius: '8px',
            cursor: 'pointer',
            fontSize: '14px',
            fontWeight: 600,
            boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '8px',
          }}
          title="Analyze stock fundamentals, news, and social sentiment"
        >
          <span style={{ fontSize: '18px' }}>📊</span>
          <span>Analyse</span>
        </button>

      </div>

      {/* Analysis Panel */}
      <AnalysisPanel
        open={isAnalysisPanelOpen}
        symbol={defaultSymbol}
        timeframe={rawTimeframe}
        onClose={() => setIsAnalysisPanelOpen(false)}
        getChartState={getChartState}
      />

    </div>
  );
}
