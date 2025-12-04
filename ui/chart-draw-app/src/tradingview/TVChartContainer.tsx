import React, { useEffect, useRef } from 'react';
import datafeed from './datafeed';

// TradingView types (loaded globally via script tag)
declare const TradingView: any;

type Props = {
  symbol: string;
  timeframe: string;
};

export default function TVChartContainer({ symbol, timeframe }: Props) {
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const widgetRef = useRef<any>(null);

  const interval = mapTimeframeToInterval(timeframe);

  useEffect(() => {
    if (!chartContainerRef.current) return;

    const widgetOptions = {
      symbol: symbol,
      datafeed: datafeed,
      interval: interval,
      container: chartContainerRef.current,
      library_path: '/charting_library/charting_library/',
      locale: 'en',
      disabled_features: ['header_symbol_search', 'symbol_search_hot_key', 'header_compare', 'use_localstorage_for_settings'],
      enabled_features: [],
      fullscreen: false,
      autosize: true,
      theme: 'light',
      timezone: 'Asia/Kolkata',
      debug: false,
      auto_save_delay: 5,
      overrides: {
        'mainSeriesProperties.showCountdown': false,
      },
    };

    const tvWidget = new TradingView.widget(widgetOptions);
    widgetRef.current = tvWidget;

    tvWidget.onChartReady(() => {
      console.log(`TradingView Chart ready: ${symbol} ${timeframe}`);
    });

    return () => {
      if (widgetRef.current) {
        widgetRef.current.remove();
        widgetRef.current = null;
      }
    };
  }, [symbol, timeframe, interval]);

  return <div ref={chartContainerRef} style={{ width: '100%', height: '100%' }} />;
}

// Map your timeframe format to TradingView interval format
function mapTimeframeToInterval(timeframe: string): string {
  const mapping: Record<string, string> = {
    '1m': '1',
    '3m': '3',
    '5m': '5',
    '15m': '15',
    '30m': '30',
    '1h': '60',
    '2h': '120',
    '1d': '1D',
    '1w': '1W',
    '1M': '1M',
  };

  return mapping[timeframe.toLowerCase()] || '60';
}
