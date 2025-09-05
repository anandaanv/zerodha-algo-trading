package com.dtech.chartpattern.patterns;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.patterns.persistence.PatternTrendline;
import com.dtech.chartpattern.patterns.persistence.PatternTrendline.PatternLineSide;
import com.dtech.chartpattern.patterns.persistence.PatternTrendline.PatternType;
import com.dtech.chartpattern.patterns.persistence.PatternTrendlineRepository;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatternTrendlineService {

    private final ZigZagService zigZagService;
    private final PatternTrendlineRepository repository;

    /**
     * Detect pattern-derived trendlines from latest zigzag pivots and persist them.
     * Returns the freshly computed lines.
     */
    @Transactional
    public List<PatternTrendline> getOrRecalc(String tradingSymbol, Instrument instrument, Interval interval) {
        try {
            List<ZigZagPoint> pivots = zigZagService.getOrComputePivots(tradingSymbol, instrument, interval);
            if (pivots.size() < 4) {
                return Collections.emptyList();
            }

            // Work with the last K pivots for responsiveness
            int K = Math.min(60, pivots.size());
            List<ZigZagPoint> recent = pivots.subList(pivots.size() - K, pivots.size());

            List<ZigZagPoint> highs = recent.stream().filter(ZigZagPoint::isHigh).collect(Collectors.toList());
            List<ZigZagPoint> lows  = recent.stream().filter(ZigZagPoint::isLow).collect(Collectors.toList());

            List<PatternTrendline> out = new ArrayList<>();

            // 1) Triangle / Wedge: Build upper/lower lines from last two highs / last two lows
            Optional<PatternTrendline> upperOpt = buildBoundaryLine(tradingSymbol, interval, highs, PatternLineSide.UPPER);
            Optional<PatternTrendline> lowerOpt = buildBoundaryLine(tradingSymbol, interval, lows,  PatternLineSide.LOWER);

            if (upperOpt.isPresent() && lowerOpt.isPresent()) {
                PatternTrendline upper = upperOpt.get();
                PatternTrendline lower = lowerOpt.get();

                // Determine contracting vs expanding by gap change and slopes
                double gapStart = Math.abs(evalGapAtIndex(upper, lower, Math.min(upper.getStartIdx(), lower.getStartIdx())));
                double gapEnd   = Math.abs(evalGapAtIndex(upper, lower, Math.max(upper.getEndIdx(), lower.getEndIdx())));

                boolean contracting = gapEnd < gapStart;
                boolean upperDown = upper.getSlopePerBar() < 0;
                boolean lowerUp   = lower.getSlopePerBar() > 0;

                PatternType patternType;
                if (upperDown && lowerUp && contracting) {
                    patternType = PatternType.TRIANGLE_CONTRACTING;
                } else if ((upper.getSlopePerBar() > 0 && lower.getSlopePerBar() < 0) && !contracting) {
                    patternType = PatternType.TRIANGLE_EXPANDING;
                } else if (upper.getSlopePerBar() > 0 && lower.getSlopePerBar() > 0) {
                    patternType = PatternType.WEDGE_ASCENDING;
                } else if (upper.getSlopePerBar() < 0 && lower.getSlopePerBar() < 0) {
                    patternType = PatternType.WEDGE_DESCENDING;
                } else {
                    // Fallback classification
                    patternType = contracting ? PatternType.TRIANGLE_CONTRACTING : PatternType.TRIANGLE_EXPANDING;
                }

                String gid = UUID.randomUUID().toString();
                upper.setGroupId(gid);
                lower.setGroupId(gid);
                upper.setPatternType(patternType);
                lower.setPatternType(patternType);
                upper.setUpdatedAt(LocalDateTime.now());
                lower.setUpdatedAt(LocalDateTime.now());
                out.add(upper);
                out.add(lower);
            }

            // 2) Reversal helper lines: connect last two lower-highs (resistance) or higher-lows (support)
            recentReversalLines(tradingSymbol, interval, highs, lows).forEach(out::add);

            // Persist: replace previous computed lines for this (symbol, interval)
            repository.deleteByTradingSymbolAndInterval(tradingSymbol, interval);
            if (!out.isEmpty()) {
                repository.saveAll(out);
            }
            return out;
        } catch (Exception e) {
            log.warn("Pattern trendline calc failed for {}@{}: {}", tradingSymbol, interval, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private Optional<PatternTrendline> buildBoundaryLine(String symbol, Interval interval, List<ZigZagPoint> pivots, PatternLineSide side) {
        if (pivots.size() < 2) return Optional.empty();
        ZigZagPoint p1 = pivots.get(pivots.size() - 2);
        ZigZagPoint p2 = pivots.get(pivots.size() - 1);
        int idx1 = p1.getBarIndex();
        int idx2 = p2.getBarIndex();
        if (idx1 == idx2) return Optional.empty();

        double m = (p2.getValue() - p1.getValue()) / (idx2 - idx1);
        double c = p1.getValue() - m * idx1;

        PatternTrendline line = PatternTrendline.builder()
                .tradingSymbol(symbol)
                .interval(interval)
                .side(side)
                .startIdx(idx1)
                .endIdx(idx2)
                .startTs(p1.getTimestamp())
                .endTs(p2.getTimestamp())
                .y1(p1.getValue())
                .y2(p2.getValue())
                .slopePerBar(m)
                .intercept(c)
                .confidence(0.6) // baseline; can be refined
                .metadataJson(null)
                .updatedAt(LocalDateTime.now())
                .build();
        return Optional.of(line);
    }

    private double evalGapAtIndex(PatternTrendline upper, PatternTrendline lower, int idx) {
        double yu = upper.getSlopePerBar() * idx + upper.getIntercept();
        double yl = lower.getSlopePerBar() * idx + lower.getIntercept();
        return yu - yl;
    }

    private List<PatternTrendline> recentReversalLines(String symbol, Interval interval,
                                                       List<ZigZagPoint> highs, List<ZigZagPoint> lows) {
        List<PatternTrendline> out = new ArrayList<>();
        // Resistance across two most recent lower-highs
        if (highs.size() >= 3) {
            ZigZagPoint h2 = highs.get(highs.size() - 2);
            ZigZagPoint h3 = highs.get(highs.size() - 1);
            ZigZagPoint h1 = highs.get(highs.size() - 3);
            boolean lowerHighs = h1.getValue() > h2.getValue() && h2.getValue() > h3.getValue();
            if (lowerHighs) {
                out.add(buildSimpleLine(symbol, interval, h2, h3, PatternType.REVERSAL_RESISTANCE, PatternLineSide.RESISTANCE, 0.65));
            }
        }
        // Support across two most recent higher-lows
        if (lows.size() >= 3) {
            ZigZagPoint l2 = lows.get(lows.size() - 2);
            ZigZagPoint l3 = lows.get(lows.size() - 1);
            ZigZagPoint l1 = lows.get(lows.size() - 3);
            boolean higherLows = l1.getValue() < l2.getValue() && l2.getValue() < l3.getValue();
            if (higherLows) {
                out.add(buildSimpleLine(symbol, interval, l2, l3, PatternType.REVERSAL_SUPPORT, PatternLineSide.SUPPORT, 0.65));
            }
        }
        return out;
        }

    private PatternTrendline buildSimpleLine(String symbol, Interval interval,
                                             ZigZagPoint a, ZigZagPoint b,
                                             PatternType type, PatternLineSide side,
                                             double conf) {
        int idx1 = a.getBarIndex();
        int idx2 = b.getBarIndex();
        double m = (b.getValue() - a.getValue()) / (idx2 - idx1);
        double c = a.getValue() - m * idx1;
        return PatternTrendline.builder()
                .tradingSymbol(symbol)
                .interval(interval)
                .groupId(UUID.randomUUID().toString())
                .patternType(type)
                .side(side)
                .startIdx(idx1)
                .endIdx(idx2)
                .startTs(a.getTimestamp())
                .endTs(b.getTimestamp())
                .y1(a.getValue())
                .y2(b.getValue())
                .slopePerBar(m)
                .intercept(c)
                .confidence(conf)
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
