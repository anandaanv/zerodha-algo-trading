package com.dtech.aitrader.v2.narrative.macd;

import com.dtech.aitrader.v2.narrative.beat.Beat;
import com.dtech.aitrader.v2.narrative.beat.BeatVerb;
import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.dtech.chartdata.model.OhlcBarDTO;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Run the MACD narrative extractor on the TCS weekly fixture (no DB / Spring needed).
 * Dumps the resulting Narrative JSON to /tmp/tcs_macd_narrative.json for owner inspection.
 *
 * Acceptance asserts are minimal here — we don't have a hand-built TCS reference yet.
 * The goal is: produce a clean narrative shape and let the owner eyeball-validate.
 */
class TcsNarrativeTest {

    private static final String FIXTURE = "/fixtures/tcs_weekly_2021_2026.json";

    @Test
    void extractTcsNarrative() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new ParameterNamesModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        InputStream in = TcsNarrativeTest.class.getResourceAsStream(FIXTURE);
        assertNotNull(in, "TCS fixture missing on classpath: " + FIXTURE);

        Map<String, Object> dump = mapper.readValue(in, new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawBars = (List<Map<String, Object>>) dump.get("bars");
        List<OhlcBarDTO> bars = rawBars.stream().map(b -> new OhlcBarDTO(
                ((Number) b.get("epoch_seconds")).longValue(),
                ((Number) b.get("open")).doubleValue(),
                ((Number) b.get("high")).doubleValue(),
                ((Number) b.get("low")).doubleValue(),
                ((Number) b.get("close")).doubleValue(),
                ((Number) b.get("volume")).doubleValue()
        )).toList();
        System.out.println("[fixture] TCS bars loaded: " + bars.size());

        // Stub ZigZagService — use our own pivot engine on close-prices for the price pivots
        ZigZagService stub = Mockito.mock(ZigZagService.class);
        Mockito.when(stub.resolveParams(Mockito.anyString(), Mockito.any()))
                .thenReturn(com.dtech.chartpattern.zigzag.ZigZagParams.ofDefaults(
                        14, 2.5, 0.02, 0.7, 5, false, 1.5, 20,
                        com.dtech.chartpattern.zigzag.ZigZagParams.Mode.LIVE));
        Mockito.when(stub.detect(Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> {
                    org.ta4j.core.BarSeries series = inv.getArgument(0);
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
                });

        MacdNarrativeExtractor extractor = new MacdNarrativeExtractor(
                new com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine(stub));
        Narrative narrative = extractor.extract(bars, "TCS", "Week", MacdNarrativeParams.ofDefaults());

        // Serialize for inspection
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(narrative);
        Files.writeString(Path.of("/tmp/tcs_macd_narrative.json"), json);

        // Basic sanity — same shape expectations as RELIANCE
        assertEquals("TCS", narrative.getSymbol());
        assertEquals("Week", narrative.getTimeframe());
        assertEquals(bars.size() - 1, narrative.getLastBar().getIndex());

        assertNotNull(narrative.getTiers().getHistory());
        assertNotNull(narrative.getTiers().getRecent());
        assertNotNull(narrative.getTiers().getPresent());

        Beat currently = narrative.getTiers().getPresent().stream()
                .filter(b -> b.getWhat() == BeatVerb.CURRENTLY).findAny()
                .orElseThrow(() -> new AssertionError("TCS narrative missing currently beat"));
        assertNotNull(currently.getMacdLine());
        assertNotNull(currently.getSignalLine());
        assertNotNull(currently.getHistogram());

        // No bare CROSSED beats
        long bareCrossed = java.util.stream.Stream.of(
                narrative.getTiers().getHistory(),
                narrative.getTiers().getRecent(),
                narrative.getTiers().getPresent())
                .flatMap(List::stream)
                .filter(b -> b.getWhat() == BeatVerb.CROSSED)
                .count();
        assertEquals(0, bareCrossed);

        // Tier sort
        assertSortedByBar(narrative.getTiers().getHistory(), "history");
        assertSortedByBar(narrative.getTiers().getRecent(), "recent");
        assertSortedByBar(narrative.getTiers().getPresent(), "present");

        // Print a compact summary
        System.out.println("\n=== TCS tier composition ===");
        System.out.println("history: " + narrative.getTiers().getHistory().size() + " beats");
        narrative.getTiers().getHistory().forEach(b ->
                System.out.println("  " + b.getWhat() + " bar=" + b.getWhenBar()
                        + " (" + b.getWhenDate() + ") value=" + b.getValue()));
        System.out.println("recent: " + narrative.getTiers().getRecent().size() + " beats");
        narrative.getTiers().getRecent().forEach(b ->
                System.out.println("  " + b.getWhat() + " bar=" + b.getWhenBar()
                        + " (" + b.getWhenDate() + ") value=" + b.getValue()));
        System.out.println("present: " + narrative.getTiers().getPresent().size() + " beats");
        narrative.getTiers().getPresent().forEach(b ->
                System.out.println("  " + b.getWhat() + " bar=" + b.getWhenBar()
                        + " (" + b.getWhenDate() + ") value=" + b.getValue()));

        System.out.println("\n=== TCS currently beat ===");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(currently));

        System.out.println("\n=== Full TCS narrative written to /tmp/tcs_macd_narrative.json ===");
    }

    private static void assertSortedByBar(List<Beat> beats, String tierLabel) {
        for (int i = 1; i < beats.size(); i++) {
            assertTrue(beats.get(i).getWhenBar() >= beats.get(i - 1).getWhenBar(),
                    "tier " + tierLabel + " not sorted by whenBar at position " + i);
        }
    }
}
