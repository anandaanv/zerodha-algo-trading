package com.dtech.aitrader.v2.rules;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandWidthIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

/**
 * Lazy, per-{@link BarSeries} cache of ta4j indicator instances + value-at-index accessors.
 *
 * <p>Pass-5 confirmation rules (the EW {@code Rule 0.95} leg-examiners, indicator divergence
 * detectors, etc.) need to read indicator values at HISTORICAL bars — not just at the
 * {@code asOf} bar. Without this helper every rule rebuilds the same MACD/RSI/ADX/EMA tree per
 * evaluation, which is wasteful and (more importantly) repetitive boilerplate.
 *
 * <p>Attach one instance per {@link SymbolContext} so all rules in all passes share the same
 * indicator computations — see {@link SymbolContext#getIndicators()}.
 *
 * <p>Default periods follow what {@link ContextProbe} already uses (Wilder 14 for ADX/RSI/ATR,
 * 12/26/9 MACD, 20/50/200 EMAs, 20/2σ Bollinger). If a rule needs a non-default period it should
 * instantiate its own indicator — the accessor is for the canonical reads only.
 */
public class IndicatorAccessor {

    public static final int ADX_PERIOD = 14;
    public static final int RSI_PERIOD = 14;
    public static final int ATR_PERIOD = 14;
    public static final int EMA_FAST = 20;
    public static final int EMA_MID = 50;
    public static final int EMA_SLOW = 200;
    public static final int MACD_FAST = 12;
    public static final int MACD_SLOW = 26;
    public static final int MACD_SIGNAL = 9;
    public static final int BB_PERIOD = 20;
    public static final double BB_STDEV = 2.0;

    private final BarSeries series;
    private final ClosePriceIndicator close;

    // ── Lazily-built indicators (null until first access) ──────────────────────
    private MACDIndicator macd;
    private EMAIndicator macdSignalEma;
    private RSIIndicator rsi;
    private ADXIndicator adx;
    private PlusDIIndicator plusDi;
    private MinusDIIndicator minusDi;
    private EMAIndicator ema20;
    private EMAIndicator ema50;
    private EMAIndicator ema200;
    private ATRIndicator atr;
    private BollingerBandsMiddleIndicator bbMid;
    private BollingerBandsUpperIndicator bbUpper;
    private BollingerBandsLowerIndicator bbLower;
    private BollingerBandWidthIndicator bbWidth;

    public IndicatorAccessor(BarSeries series) {
        this.series = series;
        this.close = new ClosePriceIndicator(series);
    }

    public BarSeries series() { return series; }
    public ClosePriceIndicator close() { return close; }

    // ── MACD ──────────────────────────────────────────────────────────────────

    public double macdLine(int idx) {
        if (macd == null) macd = new MACDIndicator(close, MACD_FAST, MACD_SLOW);
        return macd.getValue(idx).doubleValue();
    }

    public double macdSignal(int idx) {
        if (macdSignalEma == null) {
            if (macd == null) macd = new MACDIndicator(close, MACD_FAST, MACD_SLOW);
            macdSignalEma = new EMAIndicator(macd, MACD_SIGNAL);
        }
        return macdSignalEma.getValue(idx).doubleValue();
    }

    public double macdHistogram(int idx) { return macdLine(idx) - macdSignal(idx); }

    /** True iff bar {@code idx} is a bullish MACD cross (line crosses above signal). */
    public boolean macdBullCrossAt(int idx) {
        if (idx < 1) return false;
        return macdLine(idx - 1) <= macdSignal(idx - 1) && macdLine(idx) > macdSignal(idx);
    }

    /** True iff bar {@code idx} is a bearish MACD cross. */
    public boolean macdBearCrossAt(int idx) {
        if (idx < 1) return false;
        return macdLine(idx - 1) >= macdSignal(idx - 1) && macdLine(idx) < macdSignal(idx);
    }

    // ── RSI ───────────────────────────────────────────────────────────────────

    public double rsi(int idx) {
        if (rsi == null) rsi = new RSIIndicator(close, RSI_PERIOD);
        return rsi.getValue(idx).doubleValue();
    }

    // ── ADX / DI ──────────────────────────────────────────────────────────────

    public double adx(int idx) {
        if (adx == null) adx = new ADXIndicator(series, ADX_PERIOD);
        return adx.getValue(idx).doubleValue();
    }

    public double plusDi(int idx) {
        if (plusDi == null) plusDi = new PlusDIIndicator(series, ADX_PERIOD);
        return plusDi.getValue(idx).doubleValue();
    }

    public double minusDi(int idx) {
        if (minusDi == null) minusDi = new MinusDIIndicator(series, ADX_PERIOD);
        return minusDi.getValue(idx).doubleValue();
    }

    // ── EMA stack ─────────────────────────────────────────────────────────────

    public double ema20(int idx) {
        if (ema20 == null) ema20 = new EMAIndicator(close, EMA_FAST);
        return ema20.getValue(idx).doubleValue();
    }

    public double ema50(int idx) {
        if (ema50 == null) ema50 = new EMAIndicator(close, EMA_MID);
        return ema50.getValue(idx).doubleValue();
    }

    public double ema200(int idx) {
        if (ema200 == null) ema200 = new EMAIndicator(close, EMA_SLOW);
        return ema200.getValue(idx).doubleValue();
    }

    // ── ATR ───────────────────────────────────────────────────────────────────

    public double atr(int idx) {
        if (atr == null) atr = new ATRIndicator(series, ATR_PERIOD);
        return atr.getValue(idx).doubleValue();
    }

    // ── Bollinger ─────────────────────────────────────────────────────────────

    private void ensureBollinger() {
        if (bbMid == null) {
            bbMid = new BollingerBandsMiddleIndicator(new EMAIndicator(close, BB_PERIOD));
            StandardDeviationIndicator sd = new StandardDeviationIndicator(close, BB_PERIOD);
            org.ta4j.core.num.Num k = series.numFactory().numOf(BB_STDEV);
            bbUpper = new BollingerBandsUpperIndicator(bbMid, sd, k);
            bbLower = new BollingerBandsLowerIndicator(bbMid, sd, k);
            bbWidth = new BollingerBandWidthIndicator(bbUpper, bbMid, bbLower);
        }
    }

    public double bbMiddle(int idx) { ensureBollinger(); return bbMid.getValue(idx).doubleValue(); }
    public double bbUpper(int idx)  { ensureBollinger(); return bbUpper.getValue(idx).doubleValue(); }
    public double bbLower(int idx)  { ensureBollinger(); return bbLower.getValue(idx).doubleValue(); }
    public double bbWidth(int idx)  { ensureBollinger(); return bbWidth.getValue(idx).doubleValue(); }
}
