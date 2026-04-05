import React, { useEffect, useRef } from 'react';
import datafeed from '../tradingview/datafeed';
import { mapTimeframeToInterval } from '../tradingview/intervalUtils';
import { listOverlays } from './waveLabApi';

declare const TradingView: any;

interface Props {
  symbol: string;
  timeframe: string;
}

const WaveLabChart: React.FC<Props> = ({ symbol, timeframe }) => {
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const tvWidgetRef = useRef<any>(null);

  useEffect(() => {
    if (!chartContainerRef.current) return;

    const initWidget = async () => {
      try {
        const widgetOptions = {
          symbol: symbol,
          interval: mapTimeframeToInterval(timeframe),
          datafeed: datafeed,
          container: chartContainerRef.current,
          library_path: '/charting_library/charting_library/',
          locale: 'en',
          disabled_features: [
            'use_localstorage_for_settings',
            'volume_force_overlay',
          ],
          enabled_features: ['study_templates'],
          custom_css_url: '/chart-custom.css',
          fullscreen: false,
          autosize: true,
          theme: 'light',
          timezone: 'Asia/Kolkata',
          auto_save_delay: 5,
        };

        tvWidgetRef.current = new TradingView.widget(widgetOptions);

        tvWidgetRef.current.onChartReady(() => {
          const loadOverlays = async () => {
            try {
              const overlays = await listOverlays(symbol, timeframe);
              const chart = tvWidgetRef.current?.activeChart?.();
              if (!chart) return;

              // Clear all existing drawings before adding new ones
              chart.removeAllShapes();

              for (const overlay of overlays) {
                try {
                  const drawing = JSON.parse(overlay.drawingJson);
                  const { type, points, color, label } = drawing;

                  if (!points || points.length === 0) continue;

                  if (type === 'trend_line' || type === 'line') {
                    // ZigZag segment: needs two points
                    if (points.length < 2) continue;
                    chart.createMultipointShape(
                      [
                        { time: points[0].time, price: points[0].price },
                        { time: points[1].time, price: points[1].price },
                      ],
                      {
                        shape: 'trend_line',
                        lock: true,
                        disableSelection: true,
                        overrides: {
                          linecolor: color || '#000000',
                          linewidth: 1,
                        },
                      }
                    );
                  } else if (type === 'label') {
                    // Dow Theory label: single point with text
                    chart.createShape(
                      { time: points[0].time, price: points[0].price },
                      {
                        shape: 'text',
                        lock: true,
                        disableSelection: true,
                        text: label || '',
                        overrides: {
                          color: color || '#000000',
                          fontsize: 10,
                          bold: true,
                        },
                      }
                    );
                  }
                } catch (error) {
                  console.error('Error rendering overlay:', error);
                }
              }
            } catch (error) {
              console.error('Error loading overlays:', error);
            }
          };

          loadOverlays();
        });
      } catch (error) {
        console.error('Error initializing chart widget:', error);
      }
    };

    initWidget();

    return () => {
      if (tvWidgetRef.current) {
        tvWidgetRef.current.remove();
        tvWidgetRef.current = null;
      }
    };
  }, [symbol, timeframe]);

  return <div ref={chartContainerRef} style={{ width: '100%', height: '100%' }} />;
};

export default WaveLabChart;
