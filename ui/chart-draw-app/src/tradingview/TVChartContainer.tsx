import React, { useEffect, useRef } from 'react';
import datafeed from './datafeed';
import { mapTimeframeToInterval, intervalToPeriod } from './intervalUtils';
import { createSaveLoadAdapter } from './saveLoadAdapter';

// TradingView types (loaded globally via script tag)
declare const TradingView: any;

type Study = {
  name: string;
  // Either an ordered array (deprecated by TradingView but still works for some studies)
  // or an object with named keys (preferred by current API).
  inputs?: any[] | Record<string, any>;
  resolution?: string;  // e.g. "D" for daily — plots study on different timeframe than chart
};

export type TradeMarkers = {
  entryTime: number;          // unix seconds
  entryPrice: number;
  direction: 'LONG' | 'SHORT';
  stopPrice: number;
  targetPrice: number;
  exitTime: number;           // unix seconds
  exitProfitable: boolean;
};

type Props = {
  symbol: string;
  timeframe: string;
  onChartReady?: (chart: any) => void;
  tradeMarkers?: TradeMarkers;
  visibleRange?: { from: number; to: number }; // unix seconds
  autoStudies?: Study[];
  customIndicators?: any[];  // array of CustomIndicator objects to register
  /**
   * If provided, drawings/templates are persisted to the backend via
   * /api/chart-state/drawings keyed by ({tabId}:{symbol}, layoutId, timeframe).
   * Survives page refresh. Omit to disable persistence (widget-memory only).
   */
  tabId?: string;
};

export default function TVChartContainer({
  symbol,
  timeframe,
  onChartReady,
  tradeMarkers,
  visibleRange,
  autoStudies = [],
  customIndicators = [],
  tabId,
}: Props) {
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const widgetRef = useRef<any>(null);
  const studiesAddedRef = useRef(false);

  // Stabilise volatile inputs in refs so the main effect can read the latest values
  // without putting them in its dependency array. Without this, callers that pass an
  // inline `onChartReady={(chart) => {...}}` would trigger a full widget remount on
  // every parent re-render — including unrelated state changes like a toast popping up.
  const onChartReadyRef = useRef(onChartReady);
  const autoStudiesRef = useRef(autoStudies);
  const customIndicatorsRef = useRef(customIndicators);
  // symbol/timeframe/tabId tracked in refs so the saveLoadAdapter context closure
  // reads the latest values (e.g. when the user changes symbol via TV's search bar).
  const symbolRef = useRef(symbol);
  const timeframeRef = useRef(timeframe);
  const tabIdRef = useRef(tabId);
  useEffect(() => { onChartReadyRef.current = onChartReady; }, [onChartReady]);
  useEffect(() => { autoStudiesRef.current = autoStudies; }, [autoStudies]);
  useEffect(() => { customIndicatorsRef.current = customIndicators; }, [customIndicators]);
  useEffect(() => { symbolRef.current = symbol; }, [symbol]);
  useEffect(() => { timeframeRef.current = timeframe; }, [timeframe]);
  useEffect(() => { tabIdRef.current = tabId; }, [tabId]);

  const interval = mapTimeframeToInterval(timeframe);

  useEffect(() => {
    if (!chartContainerRef.current) return;

    // Build the save/load adapter only when tabId is provided. Without it, drawings stay in
    // widget memory (the legacy behaviour). With it, drawings are scoped to
    // (tabId:symbol, layoutId, timeframe) and round-trip through /api/chart-state/drawings.
    const saveLoadAdapter = tabIdRef.current
      ? createSaveLoadAdapter(() => ({
          symbol: symbolRef.current,
          tabId: tabIdRef.current!,
          timeframe: timeframeRef.current,
        }))
      : undefined;

    const widgetOptions: any = {
      symbol: symbol,
      datafeed: datafeed,
      interval: interval,
      container: chartContainerRef.current,
      library_path: '/charting_library/charting_library/',
      locale: 'en',
      disabled_features: ['header_symbol_search', 'symbol_search_hot_key', 'header_compare'],
      enabled_features: saveLoadAdapter
        ? ['library_custom_no_powered_branding', 'saveload_separate_drawings_storage', 'items_favoriting']
        : ['library_custom_no_powered_branding'],
      custom_css_url: '/chart-custom.css',
      fullscreen: false,
      autosize: true,
      theme: 'light',
      timezone: 'Asia/Kolkata',
      debug: false,
      auto_save_delay: 5,
      overrides: {
        'mainSeriesProperties.showCountdown': false,
      },
      custom_indicators_getter: (_PineJS: any) => {
        const ci = customIndicatorsRef.current || [];
        console.log('[custom_indicators_getter] called. customIndicators count:', ci.length);
        ci.forEach((c, i) => {
          console.log(`  [${i}] name:`, c.name, 'has metainfo:', !!c.metainfo, 'has constructor:', !!c.constructor);
        });
        return Promise.resolve(ci);
      },
    };

    if (saveLoadAdapter) {
      widgetOptions.save_load_adapter = saveLoadAdapter;
      widgetOptions.load_last_chart = true;
    }

    const tvWidget = new TradingView.widget(widgetOptions);
    widgetRef.current = tvWidget;

    tvWidget.onChartReady(() => {
      try {
        const chart = tvWidget.activeChart();
        console.log(`TradingView Chart ready: ${symbol} ${timeframe}`);

        // Reset studies flag on chart ready
        studiesAddedRef.current = false;

        // Keep the saveLoadAdapter's timeframe context in sync when the trader
        // changes resolution via TV's own toolbar. Drawings save/load is scoped by
        // (tab, symbol, timeframe), so without this update, drawings on the 15m
        // would get filed under whatever timeframe the parent last passed.
        try {
          chart.onIntervalChanged().subscribe(null, () => {
            try {
              const tf = intervalToPeriod(chart.resolution());
              if (tf) timeframeRef.current = tf;
            } catch (e) { /* ignore */ }
          });
        } catch (e) { /* ignore — older TV versions */ }

        // Persist drawings on every drawing change (debounced 2s).
        // Mirrors TVChartApp's auto-save wiring so refresh restores trendlines etc.
        if (saveLoadAdapter) {
          let saveTimeout: number | null = null;
          const triggerDrawingSave = () => {
            if (saveTimeout) clearTimeout(saveTimeout);
            saveTimeout = window.setTimeout(async () => {
              try {
                const state = chart.getLineToolsState();
                await saveLoadAdapter.saveLineToolsAndGroups(undefined, chart.id(), state);
              } catch (e) {
                console.error('[TVChartContainer] auto-save drawings failed:', e);
              }
            }, 2000);
          };
          try {
            tvWidget.subscribe('drawing_event', triggerDrawingSave);
          } catch (e) {
            console.warn('[TVChartContainer] could not subscribe to drawing_event:', e);
          }
        }

        // ---- Studies ----
        const studiesNow = autoStudiesRef.current || [];
        if (!studiesAddedRef.current && studiesNow.length > 0) {
          studiesNow.forEach((study) => {
            try {
              const inputs = study.inputs;
              // For multi-timeframe studies, pass BOTH `resolution` and `interval`
              // keys — some charting_library versions accept one, some the other.
              // Resolution values like "1D" (daily) work where bare "D" silently
              // falls back to the chart's native resolution.
              const options: any = {};
              if (study.resolution) {
                options.resolution = study.resolution;
                options.interval = study.resolution;
                options.symbol = symbol; // explicit symbol so the study fetches correctly
              }
              // Create with default inputs first. We'll patch them via setInputValues afterwards
              // because passing inputs into createStudy is brittle (input IDs vary per version
              // and per-study). This pattern works for any study.
              const studyIdPromise = chart.createStudy(
                study.name,
                false,
                false,
                undefined,
                undefined,
                Object.keys(options).length > 0 ? options : undefined
              );
              console.log(
                `Study added: ${study.name}${study.resolution ? ` @ ${study.resolution}` : ''}`
              );

              // Apply custom inputs after the study is created. Keys are matched against
              // the actual input IDs returned by getInputValues() — much more reliable than
              // passing inputs to createStudy directly (input IDs vary per study).
              if (inputs && typeof inputs === 'object') {
                Promise.resolve(studyIdPromise).then((studyId: any) => {
                  if (!studyId) return;
                  try {
                    const studyApi = chart.getStudyById(studyId);
                    const currentInputs = studyApi.getInputValues();
                    const updated: any[] = [];
                    const entries = Array.isArray(inputs)
                      ? inputs.map((v: any, i: number) => [`__pos_${i}`, v])
                      : Object.entries(inputs);

                    entries.forEach(([k, v]: any) => {
                      // Match strictly by ID — the safest pattern. Don't infer position.
                      const target = currentInputs.find((c: any) => c.id === k);
                      if (target) {
                        updated.push({ id: target.id, value: v });
                      } else {
                        console.warn(`  [${study.name}] no input matches id "${k}". Available:`,
                          currentInputs.map((c: any) => c.id));
                      }
                    });

                    if (updated.length > 0) {
                      studyApi.setInputValues(updated);
                      console.log(`  [${study.name}] applied:`, updated);
                    }
                  } catch (e) {
                    console.warn(`  [${study.name}] failed to apply inputs:`, e);
                  }
                });
              }
            } catch (e) {
              console.warn(`Failed to add study ${study.name}:`, e);
            }
          });
          studiesAddedRef.current = true;
        }

        // ---- Trade markers ----
        if (tradeMarkers) {
          const GREEN = '#43a047';
          const RED = '#e53935';

          const entryColor = tradeMarkers.direction === 'LONG' ? GREEN : RED;
          const exitColor = tradeMarkers.exitProfitable ? GREEN : RED;

          // Helper — create horizontal_line and apply color overrides AFTER creation.
          // (Passing overrides into createShape options didn't apply in this version.)
          const drawHLine = (price: number, label: string, color: string) => {
            try {
              const shapePromise = chart.createShape(
                { time: tradeMarkers.entryTime, price },
                {
                  shape: 'horizontal_line',
                  text: label,
                  lock: true, disableSelection: true, disableSave: true, disableUndo: true,
                }
              );
              Promise.resolve(shapePromise).then((id: any) => {
                if (!id) return;
                try {
                  const shape = chart.getShapeById(id);
                  if (shape && shape.setProperties) {
                    shape.setProperties({
                      linecolor: color,
                      linewidth: 2,
                      linestyle: 0,
                      textcolor: color,
                      showLabel: true,
                      bold: true,
                      fontsize: 12,
                      horzLabelsAlign: 'left',
                    });
                  }
                } catch (e) {
                  console.warn(`Failed to set ${label} line properties:`, e);
                }
              });
            } catch (e) {
              console.warn(`${label} line failed:`, e);
            }
          };

          // SL — red horizontal line at stop price
          drawHLine(tradeMarkers.stopPrice, 'SL', RED);
          // TP — green horizontal line at target price
          drawHLine(tradeMarkers.targetPrice, 'TP', GREEN);
          // Entry — BLACK horizontal line at entry price
          drawHLine(tradeMarkers.entryPrice, 'Entry', '#000000');

          // Entry — dashed vertical line. Note: vertical_line overrides require the
          // `linetoolvertline.*` prefix in this charting_library version.
          try {
            const entryShapePromise = chart.createShape(
              { time: tradeMarkers.entryTime },
              {
                shape: 'vertical_line',
                lock: true, disableSelection: true, disableSave: true, disableUndo: true,
                overrides: {
                  'linetoolvertline.linecolor': entryColor,
                  'linetoolvertline.linestyle': 2,  // 0=solid, 1=dotted, 2=dashed
                  'linetoolvertline.linewidth': 2,
                  'linetoolvertline.showLabel': false,
                  'linetoolvertline.showTime': false,
                },
              }
            );
            Promise.resolve(entryShapePromise).then(
              (id) => console.log('[Entry marker] created shape id:', id, 'at time', tradeMarkers.entryTime),
              (err) => console.warn('[Entry marker] promise rejected:', err)
            );
          } catch (e) {
            console.warn('Entry vertical line failed:', e);
          }

          // Exit — solid 3px vertical line
          try {
            const exitShapePromise = chart.createShape(
              { time: tradeMarkers.exitTime },
              {
                shape: 'vertical_line',
                lock: true, disableSelection: true, disableSave: true, disableUndo: true,
                overrides: {
                  'linetoolvertline.linecolor': exitColor,
                  'linetoolvertline.linestyle': 0,
                  'linetoolvertline.linewidth': 3,
                  'linetoolvertline.showLabel': false,
                  'linetoolvertline.showTime': false,
                },
              }
            );
            Promise.resolve(exitShapePromise).then(
              (id) => console.log('[Exit marker] created shape id:', id, 'at time', tradeMarkers.exitTime),
              (err) => console.warn('[Exit marker] promise rejected:', err)
            );
          } catch (e) {
            console.warn('Exit vertical line failed:', e);
          }
        }

        // ---- Visible range ----
        // setVisibleRange is unreliable during onChartReady because bars may not be
        // fetched yet. We try twice: once now, and again after onDataLoaded fires.
        const applyRange = () => {
          if (!visibleRange) return;
          try {
            chart.setVisibleRange({ from: visibleRange.from, to: visibleRange.to });
          } catch (e) {
            console.warn('setVisibleRange failed:', e);
          }
        };

        applyRange();

        try {
          const sub = chart.onDataLoaded();
          if (sub && typeof sub.subscribe === 'function') {
            let fired = false;
            sub.subscribe(null, () => {
              if (fired) return;
              fired = true;
              // small delay so TradingView finishes its own layout pass
              setTimeout(applyRange, 50);
            });
          }
        } catch (e) {
          // best effort — already called applyRange above
        }

        // Reset drawing tool
        try { chart.selectLineTool('cursor'); } catch (e) {
          try { (tvWidget as any).selectLineTool && (tvWidget as any).selectLineTool('cursor'); } catch (e2) {}
        }

        const cb = onChartReadyRef.current;
        if (cb) cb(chart);
      } catch (e) {
        console.error('Error in onChartReady:', e);
      }
    });

    return () => {
      if (widgetRef.current) {
        widgetRef.current.remove();
        widgetRef.current = null;
      }
    };
    // Intentionally exclude onChartReady / autoStudies / customIndicators from deps —
    // they're read via refs above so callers can pass inline arrows/arrays without
    // triggering a full widget remount on every parent re-render.
  }, [symbol, timeframe, interval, tradeMarkers, visibleRange]);

  return <div ref={chartContainerRef} style={{ width: '100%', height: '100%' }} />;
}
