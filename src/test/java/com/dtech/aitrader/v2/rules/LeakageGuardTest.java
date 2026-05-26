package com.dtech.aitrader.v2.rules;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.service.copilot.MarketStructureService;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the rule engine's NO-LEAKAGE invariant: when a rule evaluates at
 * {@code asOf}, no bar with epoch &gt; end-of-day({@code asOf}) is visible inside the
 * {@link SymbolContext}.
 *
 * <p>Owner flagged this as the one missing pilot test (impl-response {@code d0429cd0} acceptance
 * criterion (b)). The {@link ContextLoader} is the single chokepoint; if it ever stops filtering
 * future bars, every "backtest result" downstream becomes a lie because rules would peek into the
 * future. This test fails fast if that filter regresses.
 *
 * <p>We mock the four Spring beans the loader depends on so the test is sub-second and isolates
 * the filter logic. The ZigZag/MarketStructure/ContextProbe results are stubs — we don't care what
 * they return, only that the bars the loader hands them are already filtered.
 */
class LeakageGuardTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long DAY_SECONDS = 86_400L;

    @Test
    void no_bar_visible_with_date_after_asOf() {
        // 400 synthetic daily bars starting 2024-01-01 IST end-of-day.
        List<OhlcBarDTO> allBars = synth(LocalDate.of(2024, 1, 1), 400);

        ChartDataService chart = mockChartReturning(allBars);
        ZigZagService zz = stubZigZag(allBars);
        MarketStructureService ms = stubMarketStructure();
        ContextProbe probe = stubProbe();

        ContextLoader loader = new ContextLoader(chart, zz, ms, probe);

        // Pick an as-of that has plenty of warmup bars before it.
        LocalDate asOf = LocalDate.of(2024, 10, 1);
        long cutoffEpoch = asOf.atTime(23, 59, 59).atZone(IST).toEpochSecond();

        SymbolContext ctx = loader.build("RELIANCE", asOf, "Day");
        assertNotNull(ctx, "context should build with 270+ surviving bars");
        assertEquals(asOf, ctx.getAsOf());

        // THE INVARIANT — no future bar visible.
        for (OhlcBarDTO b : ctx.getBars()) {
            assertTrue(b.getTime() <= cutoffEpoch,
                    "leakage: bar at epoch " + b.getTime() + " exceeds cutoff " + cutoffEpoch
                            + " (asOf=" + asOf + ")");
        }
        // And the ta4j series should mirror the same window.
        assertEquals(ctx.getBars().size(), ctx.getSeries().getBarCount());
    }

    @Test
    void boundary_bar_on_asOf_day_is_included() {
        // The end-of-day boundary is inclusive: a bar timestamped exactly at end-of-day(asOf)
        // belongs to that as_of. Anything strictly after is leakage.
        // asOf chosen far enough into the synthesised history to clear the warmup floor (220).
        LocalDate asOf = LocalDate.of(2024, 12, 15);
        long endOfDay = asOf.atTime(23, 59, 59).atZone(IST).toEpochSecond();

        List<OhlcBarDTO> bars = synth(LocalDate.of(2024, 1, 1), 400);

        // Find any bar dated exactly asOf — synth() places one daily bar per IST-day at noon IST.
        OhlcBarDTO sameDayBar = bars.stream()
                .filter(b -> LocalDate.ofInstant(Instant.ofEpochSecond(b.getTime()), IST).equals(asOf))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("fixture missing bar for " + asOf));
        assertTrue(sameDayBar.getTime() <= endOfDay,
                "synth setup bug — same-day bar should land within the as_of day");

        ContextLoader loader = new ContextLoader(
                mockChartReturning(bars), stubZigZag(bars), stubMarketStructure(), stubProbe());
        SymbolContext ctx = loader.build("RELIANCE", asOf, "Day");
        assertNotNull(ctx);
        // The same-day bar should be the LAST bar in the context (latest within the window).
        OhlcBarDTO last = ctx.getBars().get(ctx.getBars().size() - 1);
        assertEquals(sameDayBar.getTime(), last.getTime());
    }

    @Test
    void asOf_before_data_returns_null() {
        List<OhlcBarDTO> bars = synth(LocalDate.of(2024, 1, 1), 400);
        ContextLoader loader = new ContextLoader(
                mockChartReturning(bars), stubZigZag(bars), stubMarketStructure(), stubProbe());

        // as-of well BEFORE any data → all bars are post-cutoff → loader returns null.
        SymbolContext ctx = loader.build("RELIANCE", LocalDate.of(2023, 1, 1), "Day");
        assertNull(ctx, "with no bars ≤ asOf, loader must return null, not a leaky context");
    }

    @Test
    void insufficient_warmup_returns_null_not_leak() {
        // 100 bars total — below the loader's MIN_BARS_FOR_PROBE warmup floor (220).
        // Loader must REFUSE to build (return null), not silently produce a context with future bars
        // borrowed from somewhere to satisfy the warmup.
        List<OhlcBarDTO> bars = synth(LocalDate.of(2024, 1, 1), 100);
        ContextLoader loader = new ContextLoader(
                mockChartReturning(bars), stubZigZag(bars), stubMarketStructure(), stubProbe());
        SymbolContext ctx = loader.build("RELIANCE", LocalDate.of(2024, 4, 1), "Day");
        assertNull(ctx, "insufficient warmup must return null — never leak future bars");
    }

    // ───────────── helpers ─────────────────────────────────────────────────────

    /** Synthetic daily bars: one per day, timestamped 12:00 IST. Constant OHLC for simplicity. */
    private static List<OhlcBarDTO> synth(LocalDate start, int count) {
        List<OhlcBarDTO> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LocalDate d = start.plusDays(i);
            long ts = d.atTime(12, 0).atZone(IST).toEpochSecond();
            // Slight price drift so ta4j doesn't choke on perfectly flat series.
            double p = 100.0 + i * 0.1;
            out.add(new OhlcBarDTO(ts, p, p + 1, p - 1, p, 1_000));
        }
        return out;
    }

    private static ChartDataService mockChartReturning(List<OhlcBarDTO> bars) {
        ChartDataService chart = Mockito.mock(ChartDataService.class);
        Mockito.when(chart.getBars(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(false))).thenReturn(bars);
        return chart;
    }

    /** ZigZag stub — emits a single LOW pivot near the middle of the supplied bars. */
    private static ZigZagService stubZigZag(List<OhlcBarDTO> bars) {
        ZigZagService zz = Mockito.mock(ZigZagService.class);
        Mockito.when(zz.resolveParams(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(null);
        List<ZigZagPoint> pivots = new ArrayList<>();
        if (!bars.isEmpty()) {
            OhlcBarDTO mid = bars.get(bars.size() / 2);
            pivots.add(ZigZagPoint.builder()
                    .type(ZigZagPoint.Type.LOW)
                    .timestamp(Instant.ofEpochSecond(mid.getTime()))
                    .value(mid.getLow())
                    .atrAtPivot(1.0)
                    .build());
        }
        Mockito.when(zz.detect(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(pivots);
        return zz;
    }

    private static MarketStructureService stubMarketStructure() {
        MarketStructureService ms = Mockito.mock(MarketStructureService.class);
        Mockito.when(ms.analyse(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(MarketStructureData.builder().swingPoints(List.of()).build());
        return ms;
    }

    private static ContextProbe stubProbe() {
        ContextProbe probe = Mockito.mock(ContextProbe.class);
        Mockito.when(probe.compute(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(ContextProbeResult.builder()
                        .macroRegime(MacroRegime.UNKNOWN)
                        .srPosition(SrPosition.UNKNOWN)
                        .indicatorConfluence(IndicatorConfluence.UNKNOWN)
                        .build());
        return probe;
    }
}
