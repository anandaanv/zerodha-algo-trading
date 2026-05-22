package com.dtech.aitrader.v2.narrative.adx;

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

class MultiStockAdxNarrativeTest {

    @ParameterizedTest
    @ValueSource(strings = {"RELIANCE", "HDFCBANK", "TCS", "INFY", "TATASTEEL", "SBIN", "ITC",
                            "ADANIENT", "HINDUNILVR", "BAJFINANCE"})
    void extractAndDump(String symbol) throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new ParameterNamesModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fixturePath = "/fixtures/" + symbol.toLowerCase() + "_weekly_2021_2026.json";
        try (InputStream in = MultiStockAdxNarrativeTest.class.getResourceAsStream(fixturePath)) {
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
            AdxNarrativeExtractor extractor = new AdxNarrativeExtractor(engine);
            Narrative narrative = extractor.extract(bars, symbol, "Week", AdxNarrativeParams.ofDefaults());

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(narrative);
            Path out = Path.of("/tmp/" + symbol.toLowerCase() + "_adx_narrative.json");
            Files.writeString(out, json);

            int h = narrative.getTiers().getHistory().size();
            int r = narrative.getTiers().getRecent().size();
            int p = narrative.getTiers().getPresent().size();
            System.out.printf("[ADX %s] tiers: history=%d recent=%d present=%d → %s%n",
                    symbol, h, r, p, out);
        }
    }
}
