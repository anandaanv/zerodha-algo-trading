import React, { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import datafeed from './datafeed';
import TVMultiPanelChart from './TVMultiPanelChart';
import {apiFetch} from "../config/api";
import {withAuth} from "../utils/apiHelper";
import { mapTimeframeToInterval, intervalToPeriod } from './intervalUtils';

// TradingView types (loaded globally via script tag)
declare const TradingView: any;

// Helper functions for chart state persistence
async function loadChartStateFromServer(symbol: string, interval: string): Promise<any> {
  try {
    const response = await apiFetch(`/api/chart-state?symbol=${symbol}&period=${intervalToPeriod(interval)}`, withAuth());
    if (response.ok) {
      const data = await response.json();
      return data.meta || null;
    }
    return null;
  } catch (e) {
    console.error('Failed to load chart state from server:', e);
    return null;
  }
}

async function saveChartStateToServer(symbol: string, interval: string, chartData: any): Promise<void> {
  try {
    apiFetch('/api/chart-state', withAuth({
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        symbol,
        period: intervalToPeriod(interval),
        overlays: {}, // Keep empty for backward compatibility with legacy charts
        meta: chartData // Store TradingView chart data in meta
      })
    }));
  } catch (e) {
    console.error('Failed to save chart state to server:', e);
  }
}

export default function TVChartApp() {
  const [searchParams] = useSearchParams();
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const widgetRef = useRef<any>(null);

  // Parse URL parameters
  const urlSymbol = searchParams.get('script') || searchParams.get('symbol');
  const urlTimeframe = searchParams.get('timeframe') || searchParams.get('period');

  // Default values
  const defaultSymbol = urlSymbol || 'TCS';

  // Check if multiple timeframes are requested (comma-separated)
  const timeframes = urlTimeframe ? urlTimeframe.split(',').map((tf) => tf.trim()) : [];

  // If multiple timeframes, render multi-panel view
  if (timeframes.length > 1) {
    return <TVMultiPanelChart symbol={defaultSymbol} timeframes={timeframes} />;
  }

  const defaultInterval = mapTimeframeToInterval(urlTimeframe) || '60';

  useEffect(() => {
    if (!chartContainerRef.current) return;

    const widgetOptions = {
      symbol: defaultSymbol,
      datafeed: datafeed,
      interval: defaultInterval,
      container: chartContainerRef.current,
      library_path: '/charting_library/charting_library/',
      locale: 'en',
      disabled_features: ['use_localstorage_for_settings'],
      enabled_features: ['study_templates'],
      fullscreen: false,
      autosize: true,
      theme: 'light',
      timezone: 'Asia/Kolkata',
      debug: false,
      auto_save_delay: 5,
    };

    const tvWidget = new TradingView.widget(widgetOptions);
    widgetRef.current = tvWidget;

    tvWidget.onChartReady(async () => {
      console.log('TradingView Chart is ready');

      const chart = tvWidget.activeChart();

      // Load saved state from server
      const savedState = await loadChartStateFromServer(defaultSymbol, defaultInterval);
      if (savedState) {
        try {
          tvWidget.load(savedState);
        } catch (e) {
          console.error('Failed to restore chart state:', e);
        }
      }

      // Auto-save chart state when changes occur
      let saveTimeout: number | null = null;
      const saveChartState = () => {
        if (saveTimeout) clearTimeout(saveTimeout);
        saveTimeout = window.setTimeout(() => {
          try {
            tvWidget.save((data: any) => {
              saveChartStateToServer(defaultSymbol, defaultInterval, data);
            });
          } catch (e) {
            console.error('Failed to save chart state:', e);
          }
        }, 2000); // Debounce 2 seconds
      };

      // Listen to data loaded
      chart.onDataLoaded().subscribe(null, saveChartState);

      // Subscribe to interval changes
      chart.onIntervalChanged().subscribe(null, () => {
        tvWidget.resetCache();
        chart.resetData();
        saveChartState();
      });

      // Subscribe to symbol changes
      chart.onSymbolChanged().subscribe(null, saveChartState);

      // Subscribe to drawing changes (studies, indicators, shapes, lines, etc.)
      tvWidget.subscribe('drawing_event', saveChartState);
      tvWidget.subscribe('study_event', saveChartState);
      tvWidget.subscribe('onAutoSaveNeeded', saveChartState);
    });

    return () => {
      if (widgetRef.current) {
        widgetRef.current.remove();
        widgetRef.current = null;
      }
    };
  }, [defaultSymbol, defaultInterval]);

  return (
    <div style={{ width: '100%', height: '100vh', position: 'relative' }}>
      <div ref={chartContainerRef} style={{ width: '100%', height: '100%' }} />
    </div>
  );
}
