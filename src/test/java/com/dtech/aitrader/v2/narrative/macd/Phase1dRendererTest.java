package com.dtech.aitrader.v2.narrative.macd;

import com.dtech.aitrader.v2.narrative.beat.Beat;
import com.dtech.aitrader.v2.narrative.beat.BeatVerb;
import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.aitrader.v2.narrative.support.PriceContextBuilder;
import com.dtech.algo.series.Interval;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartpattern.persistence.ZigZagSnapshotRepository;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1d — fixture-based diff test for the MACD narrative extractor.
 *
 * Loads RELIANCE weekly OHLC from a checked-in JSON fixture (no DB / Spring needed),
 * runs the extractor, and diffs the resulting Narrative against the reference acceptance
 * criteria distilled from the reference memsys output (d1f56d5c-...).
 *
 * Tests reproduce the Phase 1c acceptance criteria but without SpringBootTest, so each
 * iteration is sub-second.
 */
class Phase1dRendererTest {

    private static final String FIXTURE = "/fixtures/reliance_weekly_2021_2026.json";

    private static List<OhlcBarDTO> bars;
    private static ObjectMapper mapper;

    @BeforeAll
    static void loadFixture() throws Exception {
        mapper = new ObjectMapper()
                .registerModule(new ParameterNamesModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        InputStream in = Phase1dRendererTest.class.getResourceAsStream(FIXTURE);
        if (in == null) throw new IllegalStateException("Fixture not found on classpath: " + FIXTURE);
        Map<String, Object> dump = mapper.readValue(in, new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawBars = (List<Map<String, Object>>) dump.get("bars");
        bars = rawBars.stream().map(b -> new OhlcBarDTO(
                ((Number) b.get("epoch_seconds")).longValue(),
                ((Number) b.get("open")).doubleValue(),
                ((Number) b.get("high")).doubleValue(),
                ((Number) b.get("low")).doubleValue(),
                ((Number) b.get("close")).doubleValue(),
                ((Number) b.get("volume")).doubleValue()
        )).toList();
        System.out.println("[fixture] loaded " + bars.size() + " RELIANCE weekly bars");
    }

    /**
     * Build an extractor with a stub ZigZagService that uses the same DefaultSeriesPivotEngine
     * applied to the high/low price-series. Avoids needing the full Spring context.
     */
    private MacdNarrativeExtractor buildExtractor() {
        // ZigZagService is required for the constructor but its detect() is called via the
        // extractor's computePricePivots. For a fixture test, we mock it to return pivots
        // computed by our own pivot engine on the price series (close-based).
        ZigZagService stub = Mockito.mock(ZigZagService.class);
        Mockito.when(stub.resolveParams(Mockito.anyString(), Mockito.any()))
                .thenReturn(com.dtech.chartpattern.zigzag.ZigZagParams.ofDefaults(
                        14, 2.5, 0.02, 0.7, 5, false, 1.5, 20,
                        com.dtech.chartpattern.zigzag.ZigZagParams.Mode.LIVE));
        // For the price pivots, we'll let the extractor's own logic run; we just need
        // ZigZagService.detect to return something sensible. Build close-based pivots here.
        Mockito.when(stub.detect(Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> {
                    org.ta4j.core.BarSeries series = inv.getArgument(0);
                    return buildPricePivotsFromBarSeries(series);
                });
        return new MacdNarrativeExtractor(
                new com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine(stub));
    }

    /** Tiny adaptation: detect price pivots on close series via DefaultSeriesPivotEngine. */
    private List<ZigZagPoint> buildPricePivotsFromBarSeries(org.ta4j.core.BarSeries series) {
        int n = series.getBarCount();
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) closes[i] = series.getBar(i).getClosePrice().doubleValue();
        var engine = new com.dtech.aitrader.v2.narrative.pivot.DefaultSeriesPivotEngine();
        var pivots = engine.detect(closes,
                new com.dtech.aitrader.v2.narrative.pivot.SignificanceParams(
                        14, 6.0, 0.02, 0.7, 3, false, 1.5, 20));
        return pivots.stream().map(p -> ZigZagPoint.builder()
                .type(p.kind() == com.dtech.aitrader.v2.narrative.pivot.PivotKind.PEAK
                        ? ZigZagPoint.Type.HIGH : ZigZagPoint.Type.LOW)
                .timestamp(series.getBar(p.idx()).getEndTime())
                .barIndex(p.idx())
                .sequence(series.getBar(p.idx()).getEndTime().getEpochSecond())
                .value(p.value())
                .atrAtPivot(p.atrAtPivot())
                .build()
        ).toList();
    }

    @Test
    void testReferenceShapeAndHeadlineDivergence() throws Exception {
        MacdNarrativeExtractor extractor = buildExtractor();
        Narrative narrative = extractor.extract(bars, "RELIANCE", "Week", MacdNarrativeParams.ofDefaults());

        // Serialize for inspection
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(narrative);
        Files.writeString(Path.of("/tmp/reliance_macd_narrative_phase1d.json"), json);

        // Round-trip
        Narrative roundTripped = mapper.readValue(json, Narrative.class);
        assertEquals(narrative.getSymbol(), roundTripped.getSymbol());
        assertEquals(narrative.getTiers().getHistory().size(),
                     roundTripped.getTiers().getHistory().size());

        // --- Acceptance asserts vs reference ---

        // A) Tier shape: history sparse, recent fuller, present last few
        int historyCount = narrative.getTiers().getHistory().size();
        int recentCount = narrative.getTiers().getRecent().size();
        int presentCount = narrative.getTiers().getPresent().size();
        System.out.println("Tier counts — history=" + historyCount
                + " recent=" + recentCount + " present=" + presentCount);
        assertTrue(historyCount <= 12, "history tier too large: " + historyCount);
        assertTrue(historyCount >= 3, "history tier too small: " + historyCount);

        // The owner's explicit ask: the bar-196 regime_change (their bar 197 — the
        // "clearest regime change in the data", held 29 bars into a deep negative trough)
        // MUST appear somewhere in the narrative. This was a real bug — checkRegimePersistence
        // was capping the count at threshold, and the HISTORY tier filter wasn't keeping
        // regime_change beats at all. Fixed in this phase.
        Beat bar196Regime = java.util.stream.Stream.of(
                narrative.getTiers().getHistory(),
                narrative.getTiers().getRecent(),
                narrative.getTiers().getPresent())
                .flatMap(List::stream)
                .filter(b -> b.getWhat() == BeatVerb.REGIME_CHANGE
                        && b.getWhenBar() >= 194 && b.getWhenBar() <= 199)
                .findAny()
                .orElseThrow(() -> new AssertionError(
                        "Bar ~196 regime_change missing — owner-flagged bug (memsys reference). "
                        + "MACD goes from +8.78 to -1.92 at bar 196 and stays negative for 29 bars."));
        assertTrue(bar196Regime.getPersistedBars() >= 20,
                "bar 196 regime persistedBars should reflect actual run (~29), got "
                        + bar196Regime.getPersistedBars());
        assertTrue(presentCount >= 1, "present must have at least the currently beat");

        // B) The headline divergence — bar ~258 (our equivalent of reference's bar 260)
        Beat headline = narrative.getTiers().getRecent().stream()
                .filter(b -> b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE
                        && "bearish".equals(b.getDirection())
                        && b.getWhenBar() >= 255 && b.getWhenBar() <= 262)
                .findAny()
                .orElse(null);
        assertNotNull(headline, "Headline bearish divergence at bar ~258 missing");
        assertEquals(2, headline.getPivotPair().size());
        assertTrue(headline.getPivotPair().get(0).getBar() >= 230 && headline.getPivotPair().get(0).getBar() <= 240,
                "Divergence p1 bar outside [230,240]");
        assertTrue(headline.getPivotPair().get(1).getBar() >= 255 && headline.getPivotPair().get(1).getBar() <= 262,
                "Divergence p2 bar outside [255,262]");
        // Bearish ordering: p2.macd < p1.macd AND p2.price > p1.price
        assertTrue(headline.getPivotPair().get(1).getMacd() < headline.getPivotPair().get(0).getMacd(),
                "Bearish ordering violated: p2.macd not less than p1.macd");
        assertTrue(headline.getPivotPair().get(1).getPrice() > headline.getPivotPair().get(0).getPrice(),
                "Bearish ordering violated: p2.price not greater than p1.price");
        // Deeper anchor referring to the 2024 high (near bar 165)
        assertNotNull(headline.getDeeperAnchor(), "Headline divergence missing deeperAnchor");
        assertTrue(headline.getDeeperAnchor().getBar() >= 160 && headline.getDeeperAnchor().getBar() <= 170);
        assertTrue(headline.getDeeperAnchor().getMacd() >= 70.0);

        // C) Currently beat present at last bar
        Beat currently = narrative.getTiers().getPresent().stream()
                .filter(b -> b.getWhat() == BeatVerb.CURRENTLY)
                .findAny()
                .orElseThrow(() -> new AssertionError("currently beat missing from PRESENT tier"));
        assertNotNull(currently.getMacdLine());
        assertNotNull(currently.getSignalLine());
        assertNotNull(currently.getHistogram());
        assertEquals(bars.size() - 1, currently.getWhenBar(),
                "currently beat should be at the last bar index");

        // D) No bare CROSSED beats anywhere — they should be promoted or dropped
        long bareCrossed = java.util.stream.Stream.of(
                narrative.getTiers().getHistory(),
                narrative.getTiers().getRecent(),
                narrative.getTiers().getPresent())
                .flatMap(List::stream)
                .filter(b -> b.getWhat() == BeatVerb.CROSSED)
                .count();
        assertEquals(0, bareCrossed, "bare CROSSED beats should not appear in final output");

        // E) Coordinate integrity — bar0_date matches first fixture bar
        assertEquals("2021-01-06", narrative.getBar0Date());
        assertEquals(bars.size() - 1, narrative.getLastBar().getIndex());

        // F) Beats sorted by whenBar within each tier
        assertSortedByBar(narrative.getTiers().getHistory(), "history");
        assertSortedByBar(narrative.getTiers().getRecent(), "recent");
        assertSortedByBar(narrative.getTiers().getPresent(), "present");

        // Print a compact summary for the user
        System.out.println("\n=== Headline divergence ===");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(headline));
        System.out.println("\n=== Currently beat ===");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(currently));
        System.out.println("\n=== Full narrative written to /tmp/reliance_macd_narrative_phase1d.json ===");
    }

    private static void assertSortedByBar(List<Beat> beats, String tierLabel) {
        for (int i = 1; i < beats.size(); i++) {
            assertTrue(beats.get(i).getWhenBar() >= beats.get(i - 1).getWhenBar(),
                    "tier " + tierLabel + " not sorted by whenBar at position " + i);
        }
    }
}
