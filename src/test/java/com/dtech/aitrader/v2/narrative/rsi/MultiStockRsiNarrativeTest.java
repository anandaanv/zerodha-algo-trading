package com.dtech.aitrader.v2.narrative.rsi;

import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine;
import com.dtech.aitrader.v2.narrative.pivot.DefaultSeriesPivotEngine;
import com.dtech.aitrader.v2.narrative.pivot.PivotKind;
import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
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
 * Bulk RSI narrative extraction for the 10-stock weekly fixtures. Mirrors the MACD MultiStockTest
 * so owner gets a per-indicator slice for validation.
 *
 * <p>Stub ZigZagService uses {@link DefaultSeriesPivotEngine} on close prices — same setup as the
 * MACD MultiStockTest, so the price-context swing-state labels are honest (real engine, not proxy).
 */
class MultiStockRsiNarrativeTest {

    @ParameterizedTest
    @ValueSource(strings = {"RELIANCE", "HDFCBANK", "TCS", "INFY", "TATASTEEL", "SBIN", "ITC",
                            "ADANIENT", "HINDUNILVR", "BAJFINANCE"})
    void extractAndDump(String symbol) throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new ParameterNamesModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fixturePath = "/fixtures/" + symbol.toLowerCase() + "_weekly_2021_2026.json";
        try (InputStream in = MultiStockRsiNarrativeTest.class.getResourceAsStream(fixturePath)) {
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

            ZigZagService stub = Mockito.mock(ZigZagService.class);
            Mockito.when(stub.resolveParams(Mockito.anyString(), Mockito.any()))
                    .thenReturn(ZigZagParams.ofDefaults(14, 2.5, 0.02, 0.7, 5, false, 1.5, 20,
                            ZigZagParams.Mode.LIVE));
            Mockito.when(stub.detect(Mockito.any(), Mockito.any()))
                    .thenAnswer(inv -> {
                        org.ta4j.core.BarSeries series = inv.getArgument(0);
                        int n = series.getBarCount();
                        double[] closes = new double[n];
                        for (int i = 0; i < n; i++) closes[i] = series.getBar(i).getClosePrice().doubleValue();
                        var engine = new DefaultSeriesPivotEngine();
                        var pivots = engine.detect(closes,
                                new SignificanceParams(14, 6.0, 0.02, 0.7, 3, false, 1.5, 20));
                        return pivots.stream().map(p -> ZigZagPoint.builder()
                                .type(p.kind() == PivotKind.PEAK ?
                                        ZigZagPoint.Type.HIGH : ZigZagPoint.Type.LOW)
                                .timestamp(series.getBar(p.idx()).getEndTime())
                                .barIndex(p.idx())
                                .sequence(series.getBar(p.idx()).getEndTime().getEpochSecond())
                                .value(p.value())
                                .atrAtPivot(p.atrAtPivot())
                                .build()
                        ).toList();
                    });

            DescriptiveNarrativeEngine engine = new DescriptiveNarrativeEngine(stub);
            RsiNarrativeExtractor extractor = new RsiNarrativeExtractor(engine);
            Narrative narrative = extractor.extract(bars, symbol, "Week",
                    RsiNarrativeParams.ofDefaults());

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(narrative);
            Path out = Path.of("/tmp/" + symbol.toLowerCase() + "_rsi_narrative.json");
            Files.writeString(out, json);

            assertEquals(symbol, narrative.getSymbol());
            assertEquals("Week", narrative.getTimeframe());
            assertEquals(bars.size() - 1, narrative.getLastBar().getIndex());
            assertNotNull(narrative.getTiers().getPresent());
            assertFalse(narrative.getTiers().getPresent().isEmpty(),
                    "PRESENT tier should have at least the currently beat");

            int h = narrative.getTiers().getHistory().size();
            int r = narrative.getTiers().getRecent().size();
            int p = narrative.getTiers().getPresent().size();
            System.out.printf("[RSI %s] tiers: history=%d recent=%d present=%d  → %s%n",
                    symbol, h, r, p, out);
        }
    }
}
