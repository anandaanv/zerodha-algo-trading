package com.dtech.kitecon.analysis;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a compact nested pivot hierarchy string for AI prompts.
 *
 * Higher-TF pivots act as containers for lower-TF pivots that fall within
 * their time segment (between consecutive higher-TF pivots). This eliminates
 * flat repetition and reduces token usage significantly.
 *
 * Format example (each sub-level indented 2 spaces):
 *   PIVOTS [1d→4h→1h] px=26500
 *   1d Z0H:25400 24-01-05
 *   1d Z1L:22800 24-03-15 -10%
 *     4h z0H:25400 24-01-05
 *     4h z1L:23100 24-01-20 -9%
 *     4h z2H:24800 24-02-10 +7%
 *     4h z3L:22800 24-03-15 -8% ←
 *       1h y0H:23800 24-03-10.09
 *       1h y1L:22800 24-03-15.14 -3% ←
 *   1d Z2H:28400 24-06-20 +24% ←
 *     ...
 *
 * Token budget:
 *   - Level 0 (highest TF): all pivots
 *   - Level 1: all pivots per segment, last 4 segments of level-0
 *   - Level 2: max 8 pivots per segment, last 2 segments of level-1
 *   - Level 3+: max 6 pivots per segment, last 1 segment of parent
 */
@Service
@Slf4j
public class PivotHierarchyBuilder {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // Pivot labels by depth: Z=0, z=1, y=2, x=3
    private static final char[] LABELS = {'Z', 'z', 'y', 'x'};
    // Max segments to recurse into at each depth level
    private static final int[] MAX_SEGMENTS = {Integer.MAX_VALUE, 4, 2, 1};
    // Max sub-pivots per segment at each depth level (0=unlimited)
    private static final int[] MAX_SUB_PIVOTS = {0, 0, 8, 6};

    /**
     * Build the nested pivot section string.
     *
     * @param pivotsByTf  map of timeframe → ordered ZigZag pivots (oldest first)
     * @param tfOrder     timeframes ordered highest-degree first, e.g. ["1w","1d","4h","1h"]
     * @param currentPrice current price of the instrument
     * @return compact nested pivot text
     */
    public String build(Map<String, List<ZigZagPoint>> pivotsByTf,
                        List<String> tfOrder, double currentPrice) {
        return build(pivotsByTf, tfOrder, currentPrice, null);
    }

    /**
     * Build the nested pivot section string with optional wave labels.
     *
     * @param pivotsByTf  map of timeframe → ordered ZigZag pivots (oldest first)
     * @param tfOrder     timeframes ordered highest-degree first, e.g. ["1w","1d","4h","1h"]
     * @param currentPrice current price of the instrument
     * @param waveLabels  optional map of (timeframe → (barIndex → wave label string)) for inline annotation
     * @return compact nested pivot text
     */
    public String build(Map<String, List<ZigZagPoint>> pivotsByTf,
                        List<String> tfOrder, double currentPrice,
                        Map<String, Map<Integer, String>> waveLabels) {

        if (pivotsByTf == null || pivotsByTf.isEmpty() || tfOrder == null || tfOrder.isEmpty()) {
            return "";
        }

        // Filter to only TFs that have data, preserving order
        List<String> availableTfs = tfOrder.stream()
                .filter(pivotsByTf::containsKey)
                .collect(Collectors.toList());

        if (availableTfs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        String tfChain = String.join("→", availableTfs);
        sb.append("PIVOTS [").append(tfChain).append("] px=")
          .append(String.format("%.0f", currentPrice)).append("\n");

        // Start recursive rendering from the top-level TF
        String topTf = availableTfs.get(0);
        List<ZigZagPoint> topPivots = pivotsByTf.get(topTf);
        if (topPivots == null || topPivots.isEmpty()) return sb.toString();

        renderLevel(sb, availableTfs, pivotsByTf, 0, topPivots,
                    null, null, currentPrice, waveLabels);

        return sb.toString();
    }

    /**
     * Recursively render pivots at a given depth level.
     *
     * @param sb           output builder
     * @param tfs          ordered TF list
     * @param pivotsByTf   all pivots by TF
     * @param depth        current depth (0 = top TF)
     * @param pivots       pivots to render at this depth
     * @param segmentStart start of time window (null = from beginning)
     * @param segmentEnd   end of time window (null = no limit)
     * @param currentPrice for marking the current segment
     * @param waveLabels   optional map of (timeframe → (barIndex → wave label)) for inline annotation
     */
    private void renderLevel(StringBuilder sb,
                             List<String> tfs,
                             Map<String, List<ZigZagPoint>> pivotsByTf,
                             int depth,
                             List<ZigZagPoint> pivots,
                             Instant segmentStart,
                             Instant segmentEnd,
                             double currentPrice,
                             Map<String, Map<Integer, String>> waveLabels) {

        String tf = tfs.get(depth);
        String indent = "  ".repeat(depth);
        char labelChar = depth < LABELS.length ? LABELS[depth] : 'p';
        boolean hasSubTf = depth + 1 < tfs.size();
        String subTf = hasSubTf ? tfs.get(depth + 1) : null;
        List<ZigZagPoint> subPivots = hasSubTf ? pivotsByTf.get(subTf) : null;

        // Apply max sub-pivot limit for this depth
        List<ZigZagPoint> filtered = pivots;
        int maxSub = depth < MAX_SUB_PIVOTS.length ? MAX_SUB_PIVOTS[depth] : 6;
        if (maxSub > 0 && filtered.size() > maxSub) {
            filtered = filtered.subList(filtered.size() - maxSub, filtered.size());
        }

        // Determine which segments (pivots) to recurse into
        int maxSegs = depth < MAX_SEGMENTS.length ? MAX_SEGMENTS[depth] : 1;
        // The last maxSegs pivots are the ones we'll recurse into
        int recurseFrom = Math.max(0, filtered.size() - maxSegs);

        ZigZagPoint prev = null;
        for (int i = 0; i < filtered.size(); i++) {
            ZigZagPoint p = filtered.get(i);
            boolean isLast = (i == filtered.size() - 1);

            // Change % vs previous at same level
            String chg = "";
            if (prev != null && prev.getValue() != 0) {
                int pct = (int) Math.round((p.getValue() - prev.getValue()) / prev.getValue() * 100.0);
                chg = (pct >= 0 ? " +" : " ") + pct + "%";
            }

            String dateStr = formatDate(p.getTimestamp(), tf);
            String lastMark = isLast ? " ←" : "";

            // Look up wave label if available
            String waveLabel = "";
            if (waveLabels != null && waveLabels.containsKey(tf)) {
                Map<Integer, String> tfWaveLabels = waveLabels.get(tf);
                String label = tfWaveLabels.get(p.getBarIndex());
                if (label != null) {
                    waveLabel = " [" + label + "]";
                }
            }

            sb.append(indent)
              .append(tf).append(" ")
              .append(labelChar).append(i)
              .append(p.isHigh() ? "H:" : "L:")
              .append(String.format("%.0f", p.getValue()))
              .append(" ").append(dateStr)
              .append(chg)
              .append(waveLabel)
              .append(lastMark)
              .append("\n");

            // Recurse into sub-TF if applicable
            if (hasSubTf && subPivots != null && !subPivots.isEmpty() && i >= recurseFrom) {
                Instant subStart = prev != null ? prev.getTimestamp() : null;
                Instant subEnd = p.getTimestamp();
                List<ZigZagPoint> segSubPivots = filterByTimeRange(subPivots, subStart, subEnd);
                if (!segSubPivots.isEmpty()) {
                    renderLevel(sb, tfs, pivotsByTf, depth + 1, segSubPivots,
                                subStart, subEnd, currentPrice, waveLabels);
                }
            }

            prev = p;
        }
    }

    /**
     * Filter pivots whose timestamp falls in (start, end].
     * If start is null, includes from the beginning up to end.
     */
    private List<ZigZagPoint> filterByTimeRange(List<ZigZagPoint> pivots,
                                                 Instant start, Instant end) {
        return pivots.stream()
                .filter(p -> {
                    if (p.getTimestamp() == null) return false;
                    if (end != null && p.getTimestamp().isAfter(end)) return false;
                    if (start != null && !p.getTimestamp().isAfter(start)) return false;
                    return true;
                })
                .collect(Collectors.toList());
    }

    private String formatDate(Instant ts, String tf) {
        if (ts == null) return "?";
        boolean intraday = isIntradayTf(tf);
        if (intraday) {
            // MM-dd.HH (e.g. "03-15.09")
            return DateTimeFormatter.ofPattern("MM-dd.HH").withZone(IST).format(ts);
        } else {
            // yy-MM-dd (e.g. "24-01-05")
            return DateTimeFormatter.ofPattern("yy-MM-dd").withZone(IST).format(ts);
        }
    }

    private boolean isIntradayTf(String tf) {
        if (tf == null) return false;
        String lo = tf.toLowerCase();
        return lo.contains("min") || lo.contains("h") || lo.equals("30m") || lo.equals("15m");
    }
}
