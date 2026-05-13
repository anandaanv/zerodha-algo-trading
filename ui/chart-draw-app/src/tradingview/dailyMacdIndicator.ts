/**
 * Custom TradingView indicator: Daily MACD plotted on intraday charts.
 * Pre-computed MACD values are looked up by date from a Map closure.
 */

export type DailyMacdMap = Map<string, { macd: number; signal: number; hist: number }>;

/**
 * Build a CustomIndicator that plots daily MACD on whatever timeframe the chart displays.
 * The indicator reads pre-computed MACD values from the dailyMacdMap (keyed by 'YYYY-MM-DD').
 *
 * @param dailyMacdMap Map of 'YYYY-MM-DD' -> { macd, signal, hist }
 * @returns TradingView CustomIndicator object
 */
export function createDailyMacdIndicator(dailyMacdMap: DailyMacdMap): any {
  return {
    name: 'Daily MACD HTF',
    metainfo: {
      _metainfoVersion: 53,
      id: 'DailyMACDHTF@tv-basicstudies-1',
      description: 'Daily MACD HTF',
      shortDescription: 'Daily MACD HTF',
      is_price_study: false,
      format: { type: 'price', precision: 4 },
      plots: [
        { id: 'plot_0', type: 'line' },
        { id: 'plot_1', type: 'line' },
        { id: 'plot_2', type: 'line' },
      ],
      defaults: {
        styles: {
          plot_0: { color: '#2196f3', linewidth: 2, plottype: 'line', visible: true },
          plot_1: { color: '#ff9800', linewidth: 2, plottype: 'line', visible: true },
          plot_2: { color: '#888888', linewidth: 2, plottype: 'histogram', visible: true },
        },
        precision: 4,
        inputs: {},
      },
      styles: {
        plot_0: { title: 'MACD', histogramBase: 0 },
        plot_1: { title: 'Signal', histogramBase: 0 },
        plot_2: { title: 'Histogram', histogramBase: 0 },
      },
      inputs: [],
    },
    constructor: function () {
      let mainCallCount = 0;
      let hitCount = 0;
      let missCount = 0;
      this.init = function (context: any, _inputCallback: any) {
        this._context = context;
        console.log('[DailyMACD] init called. dailyMacdMap size:', dailyMacdMap.size);
        if (dailyMacdMap.size > 0) {
          const first = Array.from(dailyMacdMap.keys()).slice(0, 3);
          console.log('[DailyMACD] sample keys:', first);
        }
      };
      this.main = function (context: any, _inputCallback: any) {
        this._context = context;
        mainCallCount++;

        // Extract the current bar's time
        let time = 0;
        try {
          time = context.symbol.time;
        } catch {
          if (mainCallCount <= 3) console.warn('[DailyMACD] failed to read context.symbol.time');
          return [NaN, NaN, NaN];
        }

        if (!time || time === 0) {
          if (mainCallCount <= 3) console.warn('[DailyMACD] time is 0/falsy');
          return [NaN, NaN, NaN];
        }

        const d = new Date(time);
        const year = d.getUTCFullYear();
        const month = String(d.getUTCMonth() + 1).padStart(2, '0');
        const day = String(d.getUTCDate()).padStart(2, '0');
        const key = `${year}-${month}-${day}`;

        const val = dailyMacdMap.get(key);
        if (!val) {
          missCount++;
          if (mainCallCount <= 5) {
            console.warn('[DailyMACD] miss for key', key, '(time:', time, ')');
          }
          return [NaN, NaN, NaN];
        }
        hitCount++;
        if (hitCount === 1) {
          console.log('[DailyMACD] first hit at key', key, '→', val);
        }
        if (mainCallCount % 100 === 0) {
          console.log(`[DailyMACD] progress: calls=${mainCallCount} hits=${hitCount} misses=${missCount}`);
        }
        return [val.macd, val.signal, val.hist];
      };
    },
  };
}
