package com.dtech.algo.screener.dsl;

import com.dtech.algo.screener.ScreenerContext;
import com.dtech.algo.screener.SignalCallback;
import com.dtech.algo.screener.ScreenerOutput;
import com.dtech.algo.screener.dsl.averages.Averages;
import com.dtech.algo.screener.dsl.bands.Bands;
import com.dtech.algo.screener.dsl.oscillators.Oscillators;
import com.dtech.algo.screener.dsl.trend.Trend;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntToDoubleFunction;

public class KDsl {

    private final ScreenerContext ctx;
    private final SignalCallback callback;
    private String defaultAlias = "base";

    private KDsl(ScreenerContext ctx, SignalCallback callback) {
        this.ctx = ctx;
        this.callback = callback;
    }

    public static KDsl dsl(ScreenerContext ctx, SignalCallback callback) {
        return new KDsl(ctx, callback);
    }

    public KDsl defaultAlias(String alias) {
        this.defaultAlias = alias;
        return this;
    }

    // --- Averages ---
    public SeriesExpr sma(int period) { return sma(defaultAlias, period); }
    public SeriesExpr sma(String alias, int period) { return Averages.sma(ctx, alias, period); }

    public SeriesExpr ema(int period) { return ema(defaultAlias, period); }
    public SeriesExpr ema(String alias, int period) { return Averages.ema(ctx, alias, period); }

    // --- Oscillators ---
    public SeriesExpr rsi(int period) { return rsi(defaultAlias, period); }
    public SeriesExpr rsi(String alias, int period) { return Oscillators.rsi(ctx, alias, period); }

    public SeriesExpr stochK(int kPeriod, int smoothK) { return stochK(defaultAlias, kPeriod, smoothK); }
    public SeriesExpr stochK(String alias, int kPeriod, int smoothK) { return Oscillators.stochK(ctx, alias, kPeriod, smoothK); }

    // --- Trend ---
    public Trend.Macd macd(int fast, int slow, int signal) { return macd(defaultAlias, fast, slow, signal); }
    public Trend.Macd macd(String alias, int fast, int slow, int signal) { return Trend.macd(ctx, alias, fast, slow, signal); }

    public SeriesExpr adx(int period) { return adx(defaultAlias, period); }
    public SeriesExpr adx(String alias, int period) { return Trend.adx(ctx, alias, period); }

    public SeriesExpr diPlus(int period) { return diPlus(defaultAlias, period); }
    public SeriesExpr diPlus(String alias, int period) { return Trend.diPlus(ctx, alias, period); }

    public SeriesExpr diMinus(int period) { return diMinus(defaultAlias, period); }
    public SeriesExpr diMinus(String alias, int period) { return Trend.diMinus(ctx, alias, period); }

    // --- Bands ---
    public Bands.BandsTriple bbands(int period, double mult) { return bbands(defaultAlias, period, mult); }
    public Bands.BandsTriple bbands(String alias, int period, double mult) { return Bands.bbands(ctx, alias, period, mult); }

    // --- Emission helpers (fire-and-forget) ---
    public void entryIf(boolean condition, String... tags) {
        if (condition && callback != null) {
            try { callback.onEntry(ctx, tags); } catch (Exception ignored) {}
        }
    }

    public void exitIf(boolean condition, String... tags) {
        if (condition && callback != null) {
            try { callback.onExit(ctx, tags); } catch (Exception ignored) {}
        }
    }

    public void signal(String type, Map<String, Object> meta) {
        if (callback != null) {
            try { callback.onEvent(type, ctx, meta); } catch (Exception ignored) {}
        }
    }

    // --- Result helper (optional legacy interop) ---
    public Map<String, Object> result(boolean entry) { return result(entry, false, null, Set.of(), Map.of()); }
    public Map<String, Object> result(boolean entry, boolean exit) { return result(entry, exit, null, Set.of(), Map.of()); }
    public Map<String, Object> result(boolean entry, boolean exit, Double score) { return result(entry, exit, score, Set.of(), Map.of()); }
    public Map<String, Object> result(boolean entry, boolean exit, Double score, Set<String> tags) { return result(entry, exit, score, tags, Map.of()); }
    public Map<String, Object> result(boolean entry, boolean exit, Double score, Set<String> tags, Map<String, Object> debug) {
        Map<String, Object> m = new HashMap<>();
        m.put("entry", entry);
        m.put("exit", exit);
        if (score != null) m.put("score", score);
        m.put("tags", tags != null ? tags : new HashSet<>());
        m.put("debug", debug != null ? debug : Map.of());
        return m;
    }

    // --- Output helper for Kotlin scripts ---
    public ScreenerOutput output(boolean passed) {
        return output(passed, Map.of());
    }

    public ScreenerOutput output(boolean passed, Map<String, Object> debug) {
        ScreenerOutput out = new ScreenerOutput();
        out.setPassed(passed);
        out.setDebug(debug != null ? debug : Map.of());
        return out;
    }

    // --- Convenience ---
    public static Set<String> tags(String... values) {
        Set<String> s = new HashSet<>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) s.add(v);
            }
        }
        return s;
    }

    // --- Series expression wrapper ---
    public static final class SeriesExpr {
        private final ScreenerContext ctx;
        private final IntToDoubleFunction at;

        private SeriesExpr(ScreenerContext ctx, IntToDoubleFunction at) {
            this.ctx = ctx;
            this.at = at;
        }

        public static SeriesExpr of(ScreenerContext ctx, IntToDoubleFunction at) {
            return new SeriesExpr(ctx, at);
        }

        public static SeriesExpr nan(ScreenerContext ctx) {
            return new SeriesExpr(ctx, i -> Double.NaN);
        }

        public double now() {
            int i = ctx.getNowIndex();
            return at.applyAsDouble(i);
        }

        public double prev() {
            int i = ctx.getNowIndex();
            if (i <= 0) return Double.NaN;
            return at.applyAsDouble(i - 1);
        }

        public double atOffset(int barsBack) {
            int i = ctx.getNowIndex() - barsBack;
            if (i < 0) return Double.NaN;
            return at.applyAsDouble(i);
        }

        // Cross
        public boolean crossesOver(SeriesExpr other) {
            double p1 = prev(), p2 = now();
            double q1 = other.prev(), q2 = other.now();
            if (Double.isNaN(p1) || Double.isNaN(p2) || Double.isNaN(q1) || Double.isNaN(q2)) return false;
            return p1 <= q1 && p2 > q2;
        }

        public boolean crossesUnder(SeriesExpr other) {
            double p1 = prev(), p2 = now();
            double q1 = other.prev(), q2 = other.now();
            if (Double.isNaN(p1) || Double.isNaN(p2) || Double.isNaN(q1) || Double.isNaN(q2)) return false;
            return p1 >= q1 && p2 < q2;
        }

        // Slope/shape
        public boolean slopeUp() { return !Double.isNaN(now()) && !Double.isNaN(prev()) && now() > prev(); }
        public boolean slopeDown() { return !Double.isNaN(now()) && !Double.isNaN(prev()) && now() < prev(); }
        public boolean rising(int n) { return !Double.isNaN(now()) && !Double.isNaN(atOffset(n)) && now() > atOffset(n); }
        public boolean falling(int n) { return !Double.isNaN(now()) && !Double.isNaN(atOffset(n)) && now() < atOffset(n); }

        // Comparators
        public boolean gt(SeriesExpr other) { return now() > other.now(); }
        public boolean lt(SeriesExpr other) { return now() < other.now(); }
        public boolean gte(SeriesExpr other) { return now() >= other.now(); }
        public boolean lte(SeriesExpr other) { return now() <= other.now(); }

        public boolean gt(double value) { return now() > value; }
        public boolean lt(double value) { return now() < value; }
        public boolean gte(double value) { return now() >= value; }
        public boolean lte(double value) { return now() <= value; }

        // simple arithmetic value extract
        public double value() { return now(); }
    }
}
