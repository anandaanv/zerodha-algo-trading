import React, { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import datafeed from './datafeed';
import TVMultiPanelChart from './TVMultiPanelChart';
import { mapTimeframeToInterval } from './intervalUtils';
import { createSaveLoadAdapter } from './saveLoadAdapter';

// TradingView types (loaded globally via script tag)
declare const TradingView: any;

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

    const saveLoadAdapter = createSaveLoadAdapter(defaultSymbol, defaultInterval);

    const widgetOptions = {
      symbol: defaultSymbol,
      datafeed: datafeed,
      interval: defaultInterval,
      container: chartContainerRef.current,
      library_path: '/charting_library/charting_library/',
      locale: 'en',
      disabled_features: ['use_localstorage_for_settings'],
      enabled_features: [],
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
