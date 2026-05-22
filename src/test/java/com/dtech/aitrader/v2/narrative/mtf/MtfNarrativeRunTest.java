package com.dtech.aitrader.v2.narrative.mtf;

import com.dtech.aitrader.v2.narrative.adx.AdxIndicatorConfig;
import com.dtech.aitrader.v2.narrative.adx.AdxNarrativeParams;
import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.aitrader.v2.narrative.ema.EmaIndicatorConfig;
import com.dtech.aitrader.v2.narrative.ema.EmaNarrativeParams;
import com.dtech.aitrader.v2.narrative.engine.CompactNarrativeRenderer;
import com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine;
import com.dtech.aitrader.v2.narrative.macd.MacdIndicatorConfig;
import com.dtech.aitrader.v2.narrative.macd.MacdNarrativeParams;
import com.dtech.aitrader.v2.narrative.pivot.DefaultSeriesPivotEngine;
import com.dtech.aitrader.v2.narrative.pivot.PivotKind;
import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import com.dtech.aitrader.v2.narrative.rsi.RsiIndicatorConfig;
import com.dtech.aitrader.v2.narrative.rsi.RsiNarrativeParams;
import com.dtech.aitrader.v2.narrative.stoch.StochIndicatorConfig;
import com.dtech.aitrader.v2.narrative.stoch.StochNarrativeParams;
import com.dtech.aitrader.v2.narrative.stochrsi.StochRsiIndicatorConfig;
import com.dtech.aitrader.v2.narrative.stochrsi.StochRsiNarrativeParams;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Multi-TF narrative run: 15 stocks × 3 timeframes = 45 compact memory texts written to
 * {@code /tmp/mtf/{symbol}_{tf}.md}. Each combines the 6 indicator narratives in compact form
 * (~1-1.5KB) per owner spec.
 */
class MtfNarrativeRunTest {

    @ParameterizedTest(name = "[{0}] {1}/{2}")
    @CsvSource({
            // symbol, tf_label, fixture_tf_string
            "RELIANCE,    weekly, Week",
            "RELIANCE,    daily,  Day",
            "RELIANCE,    hourly, OneHour",
            "HDFCBANK,    weekly, Week",
            "HDFCBANK,    daily,  Day",
            "HDFCBANK,    hourly, OneHour",
            "TCS,         weekly, Week",
            "TCS,         daily,  Day",
            "TCS,         hourly, OneHour",
            "INFY,        weekly, Week",
            "INFY,        daily,  Day",
            "INFY,        hourly, OneHour",
            "TATASTEEL,   weekly, Week",
            "TATASTEEL,   daily,  Day",
            "TATASTEEL,   hourly, OneHour",
            "SBIN,        weekly, Week",
            "SBIN,        daily,  Day",
            "SBIN,        hourly, OneHour",
            "ITC,         weekly, Week",
            "ITC,         daily,  Day",
            "ITC,         hourly, OneHour",
            "ADANIENT,    weekly, Week",
            "ADANIENT,    daily,  Day",
            "ADANIENT,    hourly, OneHour",
            "HINDUNILVR,  weekly, Week",
            "HINDUNILVR,  daily,  Day",
            "HINDUNILVR,  hourly, OneHour",
            "BAJFINANCE,  weekly, Week",
            "BAJFINANCE,  daily,  Day",
            "BAJFINANCE,  hourly, OneHour",
            "ICICIBANK,   weekly, Week",
            "ICICIBANK,   daily,  Day",
            "ICICIBANK,   hourly, OneHour",
            "LT,          weekly, Week",
            "LT,          daily,  Day",
            "LT,          hourly, OneHour",
            "BHARTIARTL,  weekly, Week",
            "BHARTIARTL,  daily,  Day",
            "BHARTIARTL,  hourly, OneHour",
            "MARUTI,      weekly, Week",
            "MARUTI,      daily,  Day",
            "MARUTI,      hourly, OneHour",
            "SUNPHARMA,   weekly, Week",
            "SUNPHARMA,   daily,  Day",
            "SUNPHARMA,   hourly, OneHour",
    })
    void extractCompact(String symbolRaw, String tfLabelRaw, String tfFixture) throws Exception {
        String symbol = symbolRaw.trim();
        String tfLabel = tfLabelRaw.trim();
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new ParameterNamesModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fixturePath = "/fixtures/mtf/" + symbol.toLowerCase() + "_" + tfLabel + ".json";
        try (InputStream in = MtfNarrativeRunTest.class.getResourceAsStream(fixturePath)) {
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

            ZigZagService stub = buildZigZagStub();
            DescriptiveNarrativeEngine engine = new DescriptiveNarrativeEngine(stub);

            // Run all 6 indicator configs. Note: configs are reused across TFs — the engine's
            // tier windows (present/recent) are bar-relative so they scale with whatever TF is
            // in play. For tighter per-TF tuning, future work; first cut uses single set.
            List<Narrative> ns = new ArrayList<>();
            ns.add(engine.extract(bars, symbol, tfFixture, new MacdIndicatorConfig(MacdNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new RsiIndicatorConfig(RsiNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new StochIndicatorConfig(StochNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new StochRsiIndicatorConfig(StochRsiNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new AdxIndicatorConfig(AdxNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new EmaIndicatorConfig(EmaNarrativeParams.ofDefaults())));
            // Bollinger NOT in the owner's six-indicator MTF spec — excluded.

            CompactNarrativeRenderer renderer = new CompactNarrativeRenderer();
            String bar0 = ns.get(0).getBar0Date();
            int lastIdx = ns.get(0).getLastBar().getIndex();
            String lastDate = ns.get(0).getLastBar().getDate();
            double lastClose = ns.get(0).getLastBar().getClose();
            String memory = renderer.renderMtfMemory(symbol, tfLabel, bar0, bars.size(),
                    lastIdx, lastDate, lastClose, ns);

            Path outDir = Path.of("/tmp/mtf");
            Files.createDirectories(outDir);
            Path out = outDir.resolve(symbol.toLowerCase() + "_" + tfLabel + ".md");
            Files.writeString(out, memory);
            int sizeBytes = memory.getBytes().length;
            System.out.printf("[%s %s] %d bars, %d bytes → %s%n",
                    symbol, tfLabel, bars.size(), sizeBytes, out);
        }
    }

    private ZigZagService buildZigZagStub() {
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
        return stub;
    }
}
