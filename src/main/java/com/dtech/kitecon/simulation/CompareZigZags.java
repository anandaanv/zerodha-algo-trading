package com.dtech.kitecon.simulation;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompareZigZags {

    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;

    public Map<String, Object> compare(String symbol, Interval tf) {
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        BarSeries series = zigZagService.getBarSeries(symbol, instrument, tf);
        ZigZagParams base = zigZagService.resolveParams(symbol, tf);
        ZigZagParams params = ZigZagParams.ofDefaults(
                base.getAtrLength(), base.getAtrMult(), base.getPctMin(),
                base.getHysteresis(), base.getMinBarsBetweenPivots(),
                base.isDynamicPctEnabled(), base.getVolMult(),
                base.getRvolWindow(), ZigZagParams.Mode.BACKTEST);

        // ATR-based via IncrementalZigZag
        IncrementalZigZag izz = new IncrementalZigZag(params);
        izz.initialize(series, series.getEndIndex());
        List<ZigZagPoint> izzPivots = izz.getConfirmedPivots();

        // Pattern-driven via CandidatePivotZigZag
        CandidatePivotZigZag cpzz = new CandidatePivotZigZag(params);
        for (int i = 0; i < series.getBarCount(); i++) cpzz.processBar(series, i);
        List<ZigZagPoint> cpzzPivots = cpzz.getConfirmedPivots();

        // Compare timestamps
        Set<Instant> izzTs = new HashSet<>();
        Set<Instant> cpzzTs = new HashSet<>();
        for (ZigZagPoint p : izzPivots) izzTs.add(p.getTimestamp());
        for (ZigZagPoint p : cpzzPivots) cpzzTs.add(p.getTimestamp());

        Set<Instant> common = new HashSet<>(izzTs);
        common.retainAll(cpzzTs);
        Set<Instant> izzOnly = new HashSet<>(izzTs);
        izzOnly.removeAll(cpzzTs);
        Set<Instant> cpzzOnly = new HashSet<>(cpzzTs);
        cpzzOnly.removeAll(izzTs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("totalBars", series.getBarCount());
        result.put("incrementalZigZagPivots", izzPivots.size());
        result.put("candidatePivotZigZagPivots", cpzzPivots.size());
        result.put("commonTimestamps", common.size());
        result.put("incrementalOnly", izzOnly.size());
        result.put("candidateOnly", cpzzOnly.size());
        return result;
    }
}
