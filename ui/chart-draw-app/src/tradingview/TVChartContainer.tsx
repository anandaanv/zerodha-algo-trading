import React, { useEffect, useRef } from 'react';
import datafeed from './datafeed';
import { mapTimeframeToInterval } from './intervalUtils';

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
