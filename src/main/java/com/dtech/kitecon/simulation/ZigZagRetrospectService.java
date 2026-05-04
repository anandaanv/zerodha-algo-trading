package com.dtech.kitecon.simulation;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.elliott.CandlePatternRecognizer;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZigZagRetrospectService {

    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;
    private final CandlePatternRecognizer recognizer = new CandlePatternRecognizer();

    private static final String OUTPUT_DIR = "zigzag_pivots";

    public Map<String, Object> retrospect(String symbol, Interval tf) {
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) throw new IllegalArgumentException("Instrument not found: " + symbol);

        BarSeries series = zigZagService.getBarSeries(symbol, instrument, tf);
        if (series == null || series.getBarCount() < 50) {
            throw new IllegalStateException("Insufficient bars for " + symbol + " " + tf.getUiKey());
        }

        ZigZagParams baseParams = zigZagService.resolveParams(symbol, tf);
        ZigZagParams params = ZigZagParams.ofDefaults(
                baseParams.getAtrLength(), baseParams.getAtrMult(),
                baseParams.getPctMin(), baseParams.getHysteresis(),
                baseParams.getMinBarsBetweenPivots(),
                baseParams.isDynamicPctEnabled(), baseParams.getVolMult(),
                baseParams.getRvolWindow(), ZigZagParams.Mode.BACKTEST);

        IncrementalZigZag zz = new IncrementalZigZag(params);
        zz.initialize(series, series.getEndIndex());

        List<ZigZagPoint> all = zz.getConfirmedPivots();
        int total = all.size();

        List<ZigZagPoint> strictUncaught = zz.validatePivotsAgainstPatterns(series, 0, 0);
        Set<Integer> strictUncaughtIdx = new HashSet<>();
        for (ZigZagPoint p : strictUncaught) strictUncaughtIdx.add(p.getBarIndex());
        int strictMatched = total - strictUncaught.size();

        List<ZigZagPoint> windowUncaught = zz.validatePivotsAgainstPatterns(series);
        Set<Integer> windowUncaughtIdx = new HashSet<>();
        for (ZigZagPoint p : windowUncaught) windowUncaughtIdx.add(p.getBarIndex());
        int windowMatched = total - windowUncaught.size();

        // Write CSV: bar_idx, timestamp, type, price, atr_at_pivot, strict_matched, window_matched, pattern_at_pivot
        String csvPath = OUTPUT_DIR + "/" + symbol + "_" + tf.getUiKey() + "_zigzag_pivots.csv";
        try {
            Files.createDirectories(Paths.get(OUTPUT_DIR));
            try (FileWriter fw = new FileWriter(csvPath)) {
                fw.write("bar_idx,timestamp,type,price,atr_at_pivot,strict_matched,window_matched,pattern_at_pivot\n");
                for (ZigZagPoint p : all) {
                    int idx = p.getBarIndex();
                    boolean strict = !strictUncaughtIdx.contains(idx);
                    boolean window = !windowUncaughtIdx.contains(idx);
                    String patternName = "";
                    CandlePatternRecognizer.Direction needed = (p.getType() == ZigZagPoint.Type.HIGH)
                            ? CandlePatternRecognizer.Direction.BEARISH
                            : CandlePatternRecognizer.Direction.BULLISH;
                    Optional<CandlePatternRecognizer.PatternResult> r = recognizer.detectAt(series, idx, needed);
                    if (r.isPresent()) patternName = r.get().pattern().name();
                    fw.write(String.format(Locale.US, "%d,%s,%s,%.4f,%.4f,%s,%s,%s%n",
                            idx,
                            p.getTimestamp(),
                            p.getType().name(),
                            p.getValue(),
                            p.getAtrAtPivot(),
                            strict, window, patternName));
                }
            }
            log.info("[ZigZagRetrospect] wrote pivots CSV: {} ({} rows)", csvPath, total);
        } catch (IOException e) {
            log.error("Failed to write pivots CSV: {}", e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("timeframe", tf.getUiKey());
        result.put("totalBars", series.getBarCount());
        result.put("totalPivots", total);
        result.put("strictMatched", strictMatched);
        result.put("strictMatchedPct", total > 0 ? Math.round(strictMatched * 1000.0 / total) / 10.0 : 0.0);
        result.put("windowMatched", windowMatched);
        result.put("windowMatchedPct", total > 0 ? Math.round(windowMatched * 1000.0 / total) / 10.0 : 0.0);
        result.put("trulyUncaught", windowUncaught.size());
        result.put("csvPath", Paths.get(csvPath).toAbsolutePath().toString());

        log.info("[ZigZagRetrospect] {} {}: total={} strict={} window={} uncaught={}",
                symbol, tf.getUiKey(), total, strictMatched, windowMatched, windowUncaught.size());
        return result;
    }
}
