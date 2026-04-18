package com.dtech.kitecon.elliott;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.copilot.MarketStructureService;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import com.dtech.kitecon.elliott.choch.ChochStateImpulseLabeler;
import com.dtech.kitecon.elliott.historical.HistoricalImpulseLabeler;
import com.dtech.kitecon.elliott.historical.HistoricalRawImpulseLabeler;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImpulseTrainingDataService {

    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;
    private final MarketStructureService marketStructureService;
    private final ImpulseLabeler impulseLabeler;
    private final ChochStateImpulseLabeler chochStateImpulseLabeler;
    private final HistoricalImpulseLabeler historicalImpulseLabeler;
    private final HistoricalRawImpulseLabeler historicalRawImpulseLabeler;
    private final ImpulseFeatureExtractor featureExtractor;
    private final CandleRepository candleRepository;

    private static final String OUTPUT_DIR = "elliott_train_data";

    /**
     * Generate training data for a single symbol on a given timeframe (default strategy: pivot-scan).
     * Higher TF and lower TF are derived automatically.
     *
     * @return summary string with counts
     */
    public String generateForSymbol(String symbol, Interval primaryTf) throws IOException {
        return generateForSymbol(symbol, primaryTf, "pivot-scan");
    }

    /**
     * Generate training data for a single symbol on a given timeframe with specified labeler strategy.
     * Higher TF and lower TF are derived automatically.
     *
     * @param symbol trading symbol
     * @param primaryTf primary timeframe
     * @param strategyName labeler strategy ("pivot-scan" or "choch-state")
     * @return summary string with counts
     */
    public String generateForSymbol(String symbol, Interval primaryTf, String strategyName) throws IOException {
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) return "Instrument not found: " + symbol;

        // Determine TF triplet
        Interval higherTf = getHigherTf(primaryTf);
        Interval lowerTf = getLowerTf(primaryTf);

        // Load ALL primary TF data (no lookback limit — use full history for labeling)
        BarSeries primarySeries = loadFullBarSeries(symbol, instrument, primaryTf);
        if (primarySeries == null || primarySeries.getBarCount() < 50) {
            return "Insufficient data for " + symbol + " " + primaryTf.getUiKey();
        }
        log.info("[Impulse] {} {} loaded {} bars (full history)", symbol, primaryTf.getUiKey(), primarySeries.getBarCount());

        List<Bar> primaryBars = toBarList(primarySeries);
        Map<Instant, Integer> primaryTsToIdx = buildTsToIdx(primaryBars);

        // ZigZag on primary TF (BACKTEST mode for full history)
        ZigZagParams baseParams = zigZagService.resolveParams(symbol, primaryTf);
        ZigZagParams params = ZigZagParams.ofDefaults(
                baseParams.getAtrLength(), baseParams.getAtrMult(),
                baseParams.getPctMin(), baseParams.getHysteresis(),
                baseParams.getMinBarsBetweenPivots(),
                baseParams.isDynamicPctEnabled(), baseParams.getVolMult(),
                baseParams.getRvolWindow(), ZigZagParams.Mode.BACKTEST);
        List<ZigZagPoint> pivots = zigZagService.detect(primarySeries, params);

        if (pivots.size() < 25) {
            return "Too few pivots for " + symbol + " " + primaryTf.getUiKey() + ": " + pivots.size();
        }

        // Enrich pivots with leg metrics
        impulseLabeler.enrichPivots(pivots, primaryTsToIdx);

        // Market structure analysis
        MarketStructureData structureData = marketStructureService.analyse(pivots, primaryTf.getUiKey());
        List<MarketStructurePoint> structurePoints = structureData.getSwingPoints();

        // Precompute indicators on primary TF
        Map<Integer, ImpulseFeatureExtractor.IndicatorSnapshot> primaryIndicators =
                featureExtractor.precomputeIndicators(primarySeries);

        // Load and precompute higher TF (nullable)
        Map<Integer, ImpulseFeatureExtractor.IndicatorSnapshot> htfIndicators = null;
        Map<Instant, Integer> htfTsToIdx = null;
        try {
            BarSeries htfSeries = zigZagService.getBarSeries(symbol, instrument, higherTf);
            if (htfSeries != null && htfSeries.getBarCount() > 30) {
                htfIndicators = featureExtractor.precomputeIndicators(htfSeries);
                htfTsToIdx = buildTsToIdx(toBarList(htfSeries));
            }
        } catch (Exception e) {
            log.debug("No HTF data for {} {}", symbol, higherTf);
        }

        // Load and precompute lower TF (nullable)
        Map<Integer, ImpulseFeatureExtractor.IndicatorSnapshot> ltfIndicators = null;
        Map<Instant, Integer> ltfTsToIdx = null;
        List<ZigZagPoint> ltfPivots = null;
        try {
            BarSeries ltfSeries = zigZagService.getBarSeries(symbol, instrument, lowerTf);
            if (ltfSeries != null && ltfSeries.getBarCount() > 30) {
                ltfIndicators = featureExtractor.precomputeIndicators(ltfSeries);
                ltfTsToIdx = buildTsToIdx(toBarList(ltfSeries));
                ZigZagParams ltfParams = zigZagService.resolveParams(symbol, lowerTf);
                ZigZagParams ltfBacktest = ZigZagParams.ofDefaults(
                        ltfParams.getAtrLength(), ltfParams.getAtrMult(),
                        ltfParams.getPctMin(), ltfParams.getHysteresis(),
                        ltfParams.getMinBarsBetweenPivots(),
                        ltfParams.isDynamicPctEnabled(), ltfParams.getVolMult(),
                        ltfParams.getRvolWindow(), ZigZagParams.Mode.BACKTEST);
                ltfPivots = zigZagService.detect(ltfSeries, ltfBacktest);
            }
        } catch (Exception e) {
            log.debug("No LTF data for {} {}", symbol, lowerTf);
        }

        // Label pivots with selected strategy
        ImpulseLabelStrategy strategy = pickStrategy(strategyName);
        List<ImpulseLabeler.LabeledPivot> labeled = strategy.label(pivots, primaryBars, primaryTsToIdx, structurePoints);

        // Retrieve bars-to-target map from historical labelers (empty for all others)
        Map<Instant, Integer> barsToTargetMap = new HashMap<>();
        if (strategy instanceof HistoricalImpulseLabeler hil) {
            barsToTargetMap = hil.getBarsToTargetByTimestamp();
        } else if (strategy instanceof HistoricalRawImpulseLabeler hril) {
            barsToTargetMap = hril.getBarsToTargetByTimestamp();
        }

        // Extract features and write CSV
        String csvPath = OUTPUT_DIR + "/" + symbol + "_" + primaryTf.getUiKey() + "_" + strategy.name() + "_impulse.csv";
        int wave3Count = 0, wave5Count = 0, noImpulseCount = 0;

        try (FileWriter fw = new FileWriter(csvPath)) {
            // Inject bars_to_target column after timeframe in the header
            String baseHeader = featureExtractor.getCsvHeader();
            String header = baseHeader.replace("timeframe,", "timeframe,bars_to_target,");
            fw.write(header + "\n");

            for (int i = 0; i < labeled.size(); i++) {
                ImpulseLabeler.LabeledPivot lp = labeled.get(i);
                ZigZagPoint pivot = lp.pivot();

                double[] features = featureExtractor.extractFeatures(
                        pivots, i,
                        primaryIndicators, htfIndicators, ltfIndicators,
                        structurePoints, primaryTsToIdx, htfTsToIdx, ltfTsToIdx,
                        ltfPivots);

                // Write row: timestamp, price (as % of first price for relativity), label, direction, symbol, tf, bars_to_target, features...
                double relativePrice = pivots.get(0).getValue() > 0
                        ? pivot.getValue() / pivots.get(0).getValue()
                        : pivot.getValue();

                StringBuilder row = new StringBuilder();
                row.append(pivot.getTimestamp()).append(",");
                row.append(String.format("%.6f", relativePrice)).append(",");
                row.append(lp.label()).append(",");
                row.append(lp.direction()).append(",");
                row.append(symbol).append(",");
                row.append(primaryTf.getUiKey()).append(",");

                // bars_to_target: populated for wave3_start and wave5_start, blank otherwise
                String label = lp.label();
                if (("wave3_start".equals(label) || "wave5_start".equals(label))) {
                    Integer barsToTarget = barsToTargetMap.get(pivot.getTimestamp());
                    if (barsToTarget != null) row.append(barsToTarget);
                }
                row.append(",");

                for (int f = 0; f < features.length; f++) {
                    if (Double.isNaN(features[f])) row.append("");
                    else row.append(String.format(Locale.US, "%.6f", features[f]));
                    if (f < features.length - 1) row.append(",");
                }
                fw.write(row.toString() + "\n");

                switch (lp.label()) {
                    case "wave3_start" -> wave3Count++;
                    case "wave5_start" -> wave5Count++;
                    default -> noImpulseCount++;
                }
            }
        }

        // Generate verification report
        String reportPath = OUTPUT_DIR + "/" + symbol + "_" + primaryTf.getUiKey() + "_" + strategy.name() + "_report.txt";
        writeVerificationReport(reportPath, symbol, primaryTf, pivots, labeled, wave3Count, wave5Count, noImpulseCount);

        String summary = String.format(
                "[Impulse] %s %s [%s]: %d pivots | wave3=%d, wave5=%d, no_impulse=%d | CSV→%s",
                symbol, primaryTf.getUiKey(), strategy.name(), pivots.size(), wave3Count, wave5Count, noImpulseCount, csvPath);
        log.info(summary);
        return summary;
    }

    /**
     * Generate training data for multiple symbols (default strategy: pivot-scan).
     */
    public String generateForSymbols(List<String> symbols, Interval primaryTf) {
        return generateForSymbols(symbols, primaryTf, "pivot-scan");
    }

    /**
     * Generate training data for multiple symbols with specified labeler strategy.
     */
    public String generateForSymbols(List<String> symbols, Interval primaryTf, String strategyName) {
        StringBuilder report = new StringBuilder();
        int success = 0, failed = 0;
        for (String symbol : symbols) {
            try {
                String result = generateForSymbol(symbol, primaryTf, strategyName);
                report.append(result).append("\n");
                success++;
            } catch (Exception e) {
                report.append("[Impulse] " + symbol + " FAILED: " + e.getMessage()).append("\n");
                failed++;
            }
        }
        report.append(String.format("\nTotal: %d success, %d failed", success, failed));
        return report.toString();
    }

    // ── Helpers ──

    private ImpulseLabelStrategy pickStrategy(String name) {
        return switch (name == null ? "pivot-scan" : name) {
            case "choch-state" -> chochStateImpulseLabeler;
            case "pivot-scan" -> impulseLabeler;
            case "historical" -> historicalImpulseLabeler;
            case "historical-raw" -> historicalRawImpulseLabeler;
            default -> throw new IllegalArgumentException("Unknown labeler strategy: " + name + " (expected 'pivot-scan', 'choch-state', 'historical', or 'historical-raw')");
        };
    }

    private List<Bar> toBarList(BarSeries series) {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < series.getBarCount(); i++) bars.add(series.getBar(i));
        return bars;
    }

    /**
     * Load ALL candles for a symbol + TF with no date limit.
     * Used for training data generation where we want full history.
     */
    private BarSeries loadFullBarSeries(String symbol, Instrument instrument, Interval interval) {
        Instant from = Instant.parse("2015-01-01T00:00:00Z"); // far enough back for any data
        List<com.dtech.kitecon.data.Candle> candles = candleRepository
                .findAllByInstrumentAndTimeframeAndTimestampBetween(instrument, interval, from, Instant.now());
        candles.sort(java.util.Comparator.comparing(com.dtech.kitecon.data.Candle::getTimestamp));
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();
        candles.forEach(c ->
                series.addBar(BarsLoader.getBar(c.getOpen(), c.getHigh(), c.getLow(), c.getClose(),
                        java.util.Optional.ofNullable(c.getVolume()).orElse(0L), c.getTimestamp())));
        return series;
    }

    private Map<Instant, Integer> buildTsToIdx(List<Bar> bars) {
        Map<Instant, Integer> map = new HashMap<>();
        for (int i = 0; i < bars.size(); i++) map.put(bars.get(i).getEndTime(), i);
        return map;
    }

    private Interval getHigherTf(Interval primary) {
        return switch (primary) {
            case FifteenMinute -> Interval.OneHour;
            case OneHour -> Interval.Day;
            case Day -> Interval.Day; // no higher available
            default -> Interval.Day;
        };
    }

    private Interval getLowerTf(Interval primary) {
        return switch (primary) {
            case Day -> Interval.OneHour;
            case OneHour -> Interval.FifteenMinute;
            case FifteenMinute -> Interval.FiveMinute;
            default -> Interval.FifteenMinute;
        };
    }

    private void writeVerificationReport(String path, String symbol, Interval tf,
                                          List<ZigZagPoint> pivots,
                                          List<ImpulseLabeler.LabeledPivot> labeled,
                                          int w3, int w5, int noImp) throws IOException {
        try (FileWriter fw = new FileWriter(path)) {
            fw.write("IMPULSE SCAN SUMMARY\n");
            fw.write("====================\n");
            fw.write("Stock: " + symbol + "\n");
            fw.write("Timeframe: " + tf.getUiKey() + "\n");
            fw.write("Period: " + pivots.get(0).getTimestamp() + " to " + pivots.get(pivots.size()-1).getTimestamp() + "\n");
            fw.write("Total pivots: " + pivots.size() + "\n\n");
            fw.write("Labels:\n");
            fw.write("  wave3_start: " + w3 + "\n");
            fw.write("  wave5_start: " + w5 + "\n");
            fw.write("  no_impulse:  " + noImp + "\n\n");

            fw.write("WAVE 3 STARTS\n");
            fw.write("-------------\n");
            int idx = 0;
            for (ImpulseLabeler.LabeledPivot lp : labeled) {
                if ("wave3_start".equals(lp.label())) {
                    idx++;
                    fw.write(String.format("#%d  Date: %s | Price: %.2f | Direction: %s\n    %s\n\n",
                            idx, lp.pivot().getTimestamp(), lp.pivot().getValue(),
                            lp.direction() > 0 ? "LONG" : "SHORT", lp.detail()));
                }
            }

            fw.write("\nWAVE 5 STARTS\n");
            fw.write("--------------\n");
            idx = 0;
            for (ImpulseLabeler.LabeledPivot lp : labeled) {
                if ("wave5_start".equals(lp.label())) {
                    idx++;
                    fw.write(String.format("#%d  Date: %s | Price: %.2f | Direction: %s\n    %s\n\n",
                            idx, lp.pivot().getTimestamp(), lp.pivot().getValue(),
                            lp.direction() > 0 ? "LONG" : "SHORT", lp.detail()));
                }
            }
        }
    }
}
