package com.dtech.aitrader.v2.narrative.mtf;

import com.dtech.aitrader.v2.narrative.adx.AdxIndicatorConfig;
import com.dtech.aitrader.v2.narrative.adx.AdxNarrativeParams;
import com.dtech.aitrader.v2.narrative.aroon.AroonIndicatorConfig;
import com.dtech.aitrader.v2.narrative.aroon.AroonNarrativeParams;
import com.dtech.aitrader.v2.narrative.atr.AtrIndicatorConfig;
import com.dtech.aitrader.v2.narrative.atr.AtrNarrativeParams;
import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.aitrader.v2.narrative.bollinger.BollingerIndicatorConfig;
import com.dtech.aitrader.v2.narrative.bollinger.BollingerNarrativeParams;
import com.dtech.aitrader.v2.narrative.donchian.DonchianIndicatorConfig;
import com.dtech.aitrader.v2.narrative.donchian.DonchianNarrativeParams;
import com.dtech.aitrader.v2.narrative.ema.EmaIndicatorConfig;
import com.dtech.aitrader.v2.narrative.ema.EmaNarrativeParams;
import com.dtech.aitrader.v2.narrative.engine.CompactNarrativeRenderer;
import com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine;
import com.dtech.aitrader.v2.narrative.ichimoku.IchimokuIndicatorConfig;
import com.dtech.aitrader.v2.narrative.ichimoku.IchimokuNarrativeParams;
import com.dtech.aitrader.v2.narrative.keltner.KeltnerIndicatorConfig;
import com.dtech.aitrader.v2.narrative.keltner.KeltnerNarrativeParams;
import com.dtech.aitrader.v2.narrative.macd.MacdIndicatorConfig;
import com.dtech.aitrader.v2.narrative.macd.MacdNarrativeParams;
import com.dtech.aitrader.v2.narrative.obv.ObvIndicatorConfig;
import com.dtech.aitrader.v2.narrative.obv.ObvNarrativeParams;
import com.dtech.aitrader.v2.narrative.pivot.DefaultSeriesPivotEngine;
import com.dtech.aitrader.v2.narrative.pivot.PivotKind;
import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import com.dtech.aitrader.v2.narrative.roc.RocIndicatorConfig;
import com.dtech.aitrader.v2.narrative.roc.RocNarrativeParams;
import com.dtech.aitrader.v2.narrative.rsi.RsiIndicatorConfig;
import com.dtech.aitrader.v2.narrative.rsi.RsiNarrativeParams;
import com.dtech.aitrader.v2.narrative.stoch.StochIndicatorConfig;
import com.dtech.aitrader.v2.narrative.stoch.StochNarrativeParams;
import com.dtech.aitrader.v2.narrative.stochrsi.StochRsiIndicatorConfig;
import com.dtech.aitrader.v2.narrative.stochrsi.StochRsiNarrativeParams;
import com.dtech.aitrader.v2.narrative.vwap.VwapIndicatorConfig;
import com.dtech.aitrader.v2.narrative.vwap.VwapNarrativeParams;
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
 * Multi-TF narrative run v2 (owner b3ff4ca0): 15 stocks × 4 timeframes × 14 indicators →
 * 60 compact memory texts written to {@code /tmp/mtf/{symbol}_{tf}.md}.
 *
 * <p>Indicators (14, in render order):
 * <ol>
 *   <li>MACD (FULL_NARRATIVE)</li>
 *   <li>RSI (FULL_NARRATIVE, Brown regime)</li>
 *   <li>Stochastic (FULL_NARRATIVE)</li>
 *   <li>StochRSI (FULL_NARRATIVE)</li>
 *   <li>ROC (FULL_NARRATIVE, NEW v2)</li>
 *   <li>OBV (FULL_NARRATIVE, NEW v2)</li>
 *   <li>ADX_DMI (REGIME_EPISODE)</li>
 *   <li>Aroon (REGIME_EPISODE, NEW v2)</li>
 *   <li>Bollinger (REGIME_EPISODE, built but excluded in v1)</li>
 *   <li>Keltner (REGIME_EPISODE, NEW v2)</li>
 *   <li>Donchian (REGIME_EPISODE, NEW v2)</li>
 *   <li>EMA_Stack (REGIME_EPISODE)</li>
 *   <li>ATR (SNAPSHOT, NEW v2)</li>
 *   <li>VWAP (SNAPSHOT, NEW v2)</li>
 *   <li>Ichimoku (SNAPSHOT, NEW v2)</li>
 * </ol>
 * Williams %R excluded per owner — hard-dedup into Stochastic.
 *
 * <p>Cutoff: all 4 TFs aligned to last intraday date (2026-05-18) by the dump script.
 */
class MtfNarrativeRunTest {

    @ParameterizedTest(name = "[{0}] {1}/{2}")
    @CsvSource({
            // symbol, tf_label, fixture_tf_string
            "RELIANCE,    weekly, Week",
            "RELIANCE,    daily,  Day",
            "RELIANCE,    hourly, OneHour",
            "RELIANCE,    15min,  FifteenMinute",
            "HDFCBANK,    weekly, Week",
            "HDFCBANK,    daily,  Day",
            "HDFCBANK,    hourly, OneHour",
            "HDFCBANK,    15min,  FifteenMinute",
            "TCS,         weekly, Week",
            "TCS,         daily,  Day",
            "TCS,         hourly, OneHour",
            "TCS,         15min,  FifteenMinute",
            "INFY,        weekly, Week",
            "INFY,        daily,  Day",
            "INFY,        hourly, OneHour",
            "INFY,        15min,  FifteenMinute",
            "TATASTEEL,   weekly, Week",
            "TATASTEEL,   daily,  Day",
            "TATASTEEL,   hourly, OneHour",
            "TATASTEEL,   15min,  FifteenMinute",
            "SBIN,        weekly, Week",
            "SBIN,        daily,  Day",
            "SBIN,        hourly, OneHour",
            "SBIN,        15min,  FifteenMinute",
            "ITC,         weekly, Week",
            "ITC,         daily,  Day",
            "ITC,         hourly, OneHour",
            "ITC,         15min,  FifteenMinute",
            "ADANIENT,    weekly, Week",
            "ADANIENT,    daily,  Day",
            "ADANIENT,    hourly, OneHour",
            "ADANIENT,    15min,  FifteenMinute",
            "HINDUNILVR,  weekly, Week",
            "HINDUNILVR,  daily,  Day",
            "HINDUNILVR,  hourly, OneHour",
            "HINDUNILVR,  15min,  FifteenMinute",
            "BAJFINANCE,  weekly, Week",
            "BAJFINANCE,  daily,  Day",
            "BAJFINANCE,  hourly, OneHour",
            "BAJFINANCE,  15min,  FifteenMinute",
            "ICICIBANK,   weekly, Week",
            "ICICIBANK,   daily,  Day",
            "ICICIBANK,   hourly, OneHour",
            "ICICIBANK,   15min,  FifteenMinute",
            "LT,          weekly, Week",
            "LT,          daily,  Day",
            "LT,          hourly, OneHour",
            "LT,          15min,  FifteenMinute",
            "BHARTIARTL,  weekly, Week",
            "BHARTIARTL,  daily,  Day",
            "BHARTIARTL,  hourly, OneHour",
            "BHARTIARTL,  15min,  FifteenMinute",
            "MARUTI,      weekly, Week",
            "MARUTI,      daily,  Day",
            "MARUTI,      hourly, OneHour",
            "MARUTI,      15min,  FifteenMinute",
            "SUNPHARMA,   weekly, Week",
            "SUNPHARMA,   daily,  Day",
            "SUNPHARMA,   hourly, OneHour",
            "SUNPHARMA,   15min,  FifteenMinute",
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

            // 15 indicators per owner v2 spec (Williams %R excluded per hard dedup).
            List<Narrative> ns = new ArrayList<>();
            // Full narrative (6)
            ns.add(engine.extract(bars, symbol, tfFixture, new MacdIndicatorConfig(MacdNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new RsiIndicatorConfig(RsiNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new StochIndicatorConfig(StochNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new StochRsiIndicatorConfig(StochRsiNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new RocIndicatorConfig(RocNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new ObvIndicatorConfig(ObvNarrativeParams.ofDefaults())));
            // Regime episode (6)
            ns.add(engine.extract(bars, symbol, tfFixture, new AdxIndicatorConfig(AdxNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new AroonIndicatorConfig(AroonNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new BollingerIndicatorConfig(BollingerNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new KeltnerIndicatorConfig(KeltnerNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new DonchianIndicatorConfig(DonchianNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new EmaIndicatorConfig(EmaNarrativeParams.ofDefaults())));
            // Snapshot (3)
            ns.add(engine.extract(bars, symbol, tfFixture, new AtrIndicatorConfig(AtrNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new VwapIndicatorConfig(VwapNarrativeParams.ofDefaults())));
            ns.add(engine.extract(bars, symbol, tfFixture, new IchimokuIndicatorConfig(IchimokuNarrativeParams.ofDefaults())));

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
