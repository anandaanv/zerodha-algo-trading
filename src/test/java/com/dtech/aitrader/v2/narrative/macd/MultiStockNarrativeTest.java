package com.dtech.aitrader.v2.narrative.macd;

import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.dtech.chartdata.model.OhlcBarDTO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bulk MACD narrative extraction for the 10-stock fixture set
 * (per memsys e8781cbe — minus the proxy strategy; we use our real extractor).
 *
 * For each stock:
 *   1. Load /fixtures/{symbol}_weekly_2021_2026.json
 *   2. Extract narrative via MacdNarrativeExtractor + DefaultSeriesPivotEngine
 *   3. Write /tmp/{symbol}_macd_narrative.json for memsys posting
 *
 * Owner will visually validate each narrative on memsys.
 */
class MultiStockNarrativeTest {

    @ParameterizedTest
    @ValueSource(strings = {"HDFCBANK","INFY","TATASTEEL","SBIN","ITC","ADANIENT","HINDUNILVR","BAJFINANCE"})
    void extractAndDump(String symbol) throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new ParameterNamesModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fixturePath = "/fixtures/" + symbol.toLowerCase() + "_weekly_2021_2026.json";
        try (InputStream in = MultiStockNarrativeTest.class.getResourceAsStream(fixturePath)) {
            assertNotNull(in, "Fixture missing: " + fixturePath);
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

            // Stub ZigZagService — use DefaultSeriesPivotEngine on closes for price pivots
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

            MacdNarrativeExtractor extractor = new MacdNarrativeExtractor(stub);
            Narrative narrative = extractor.extract(bars, symbol, "Week", MacdNarrativeParams.ofDefaults());

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(narrative);
            Path out = Path.of("/tmp/" + symbol.toLowerCase() + "_macd_narrative.json");
            Files.writeString(out, json);

            // Light sanity asserts (we don't have hand-crafted references for these yet)
            assertEquals(symbol, narrative.getSymbol());
            assertEquals("Week", narrative.getTimeframe());
            assertEquals(bars.size() - 1, narrative.getLastBar().getIndex());
            assertNotNull(narrative.getTiers().getPresent());
            assertFalse(narrative.getTiers().getPresent().isEmpty(), "PRESENT tier should have at least the currently beat");
            // No bare CROSSED beats anywhere
            long bareCrossed = java.util.stream.Stream.of(
                    narrative.getTiers().getHistory(),
                    narrative.getTiers().getRecent(),
                    narrative.getTiers().getPresent())
                    .flatMap(List::stream)
                    .filter(b -> b.getWhat() == com.dtech.aitrader.v2.narrative.beat.BeatVerb.CROSSED)
                    .count();
            assertEquals(0, bareCrossed);

            // Print summary
            int h = narrative.getTiers().getHistory().size();
            int r = narrative.getTiers().getRecent().size();
            int p = narrative.getTiers().getPresent().size();
            System.out.printf("[%s] tiers: history=%d recent=%d present=%d  → %s%n",
                    symbol, h, r, p, out);
        }
    }
}
