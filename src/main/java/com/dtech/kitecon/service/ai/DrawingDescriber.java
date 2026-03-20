package com.dtech.kitecon.service.ai;

import com.dtech.kitecon.service.ai.tools.ValidationInput;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Converts a TradingView drawing into a human-readable description for AI prompts.
 *
 * Each drawing family has its own describer registered in REGISTRY. To add support
 * for a new drawing type, implement a static BiFunction<String, List<Point>, String>
 * and add one entry to REGISTRY — no other code needs to change.
 *
 * For tools whose computed geometry depends on the trader's price/time scale
 * (e.g. Gann Fan) the describer notes the named lines and flags the limitation.
 * A future enhancement can extract the scale from the drawing state and compute
 * exact levels.
 */
public final class DrawingDescriber {

    private DrawingDescriber() {}

    // ── Registry ───────────────────────────────────────────────────────────────
    // LinkedHashMap preserves insertion order so more-specific matchers can be
    // placed before broader ones. Key = substring(s) that identify the family.

    private static final Map<String, BiFunction<String, List<ValidationInput.Point>, String>>
        REGISTRY = new LinkedHashMap<>();

    static {
        // Fibonacci family  (check before generic "channel" / "fan")
        REGISTRY.put("fibonacci|fib",        DrawingDescriber::describeFibonacci);

        // Pitchfork family
        REGISTRY.put("pitchfork|pitchfan|schiff", DrawingDescriber::describePitchfork);

        // Gann family
        REGISTRY.put("gann_fan|gannfan|ganfan",   DrawingDescriber::describeGannFan);
        REGISTRY.put("gann_square|gannsquare",     DrawingDescriber::describeGannSquare);

        // Elliott Wave
        REGISTRY.put("elliott|wave",         DrawingDescriber::describeElliott);

        // Harmonic patterns
        REGISTRY.put("harmonic|cypher|gartley|butterfly|bat|crab",
                                             DrawingDescriber::describeHarmonic);

        // Channel / regression
        REGISTRY.put("channel|regression",   DrawingDescriber::describeChannel);

        // Add new entries here — existing code is untouched.
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public static String describe(ValidationInput.Drawing d) {
        if (d == null) return "";
        String type = d.getType() == null ? "" : d.getType().toLowerCase();
        List<ValidationInput.Point> pts = d.getPoints();

        StringBuilder sb = new StringBuilder("- ").append(d.getType());
        appendLabel(sb, d);

        // Find the first registered family whose pattern matches the type string
        String geometry = REGISTRY.entrySet().stream()
            .filter(e -> matchesAny(type, e.getKey().split("\\|")))
            .findFirst()
            .map(e -> e.getValue().apply(type, pts))
            .orElse(describeGeneric(type, pts));

        if (geometry != null && !geometry.isBlank()) {
            sb.append(geometry);
        }

        return sb.toString();
    }

    // ── Matchers ───────────────────────────────────────────────────────────────

    private static boolean matchesAny(String type, String[] keywords) {
        for (String kw : keywords) {
            if (type.contains(kw)) return true;
        }
        return false;
    }

    // ── Fibonacci family ───────────────────────────────────────────────────────

    private static final double[] FIB_RETRACE = {0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0};
    private static final double[] FIB_EXTEND  = {1.272, 1.414, 1.618, 2.0, 2.618};

    private static String describeFibonacci(String type, List<ValidationInput.Point> pts) {
        if (pts == null || pts.size() < 2) return rawPoints(pts);

        ValidationInput.Point p0 = pts.get(0);
        ValidationInput.Point p1 = pts.get(pts.size() - 1);
        double lo    = Math.min(p0.getPrice(), p1.getPrice());
        double hi    = Math.max(p0.getPrice(), p1.getPrice());
        double range = hi - lo;
        boolean up   = p1.getPrice() > p0.getPrice();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("\n  Anchors: %.2f @ %s → %.2f @ %s",
            p0.getPrice(), ts(p0.getTimestamp()), p1.getPrice(), ts(p1.getTimestamp())));

        if (type.contains("spiral") || type.contains("arc") || type.contains("circle")) {
            sb.append(String.format(
                "\n  Radius: %.2f price units — spiral/arc expands geometrically from anchor", range));
            return sb.toString();
        }

        if (type.contains("fan") || type.contains("speed")) {
            sb.append("\n  Fan lines:");
            for (double r : FIB_RETRACE) {
                if (r == 0 || r == 1) continue;
                sb.append(String.format(" %.1f%%=%.2f", r * 100, up ? lo + r * range : hi - r * range));
            }
            return sb.toString();
        }

        if (type.contains("wedge")) {
            sb.append(String.format("\n  Converging channel between %.2f and %.2f", lo, hi));
            return sb.toString();
        }

        // Standard retracement
        sb.append("\n  Retracement levels:");
        for (double r : FIB_RETRACE) {
            sb.append(String.format(" %.1f%%=%.2f", r * 100, hi - r * range));
        }

        // Extension levels when third anchor is present
        if (pts.size() >= 3) {
            ValidationInput.Point p2 = pts.get(2);
            double move = Math.abs(p1.getPrice() - p0.getPrice());
            sb.append(String.format("\n  Extension levels (from %.2f):", p2.getPrice()));
            for (double r : FIB_EXTEND) {
                sb.append(String.format(" %.1f%%=%.2f",
                    r * 100, up ? p2.getPrice() + r * move : p2.getPrice() - r * move));
            }
        }
        return sb.toString();
    }

    // ── Pitchfork / Schiff ─────────────────────────────────────────────────────

    private static String describePitchfork(String type, List<ValidationInput.Point> pts) {
        if (pts == null || pts.size() < 3) return rawPoints(pts);
        ValidationInput.Point a = pts.get(0), b = pts.get(1), c = pts.get(2);
        double mid = (b.getPrice() + c.getPrice()) / 2.0;
        long midT  = (b.getTimestamp() + c.getTimestamp()) / 2;
        return String.format(
            "\n  Pivot (A): %.2f @ %s\n  High  (B): %.2f @ %s\n  Low   (C): %.2f @ %s" +
            "\n  Median line: %.2f @ %s → %.2f @ %s" +
            "\n  Upper parallel through B: %.2f  Lower parallel through C: %.2f",
            a.getPrice(), ts(a.getTimestamp()), b.getPrice(), ts(b.getTimestamp()),
            c.getPrice(), ts(c.getTimestamp()),
            a.getPrice(), ts(a.getTimestamp()), mid, ts(midT), b.getPrice(), c.getPrice());
    }

    // ── Gann Fan ──────────────────────────────────────────────────────────────
    // Exact price levels require the trader's price-per-bar scale (not available
    // from getLineToolsState without further work). Named angles are provided so
    // the AI can reason about the structure qualitatively.

    private static final String[] GANN_ANGLES =
        {"8×1 (82.5°)", "4×1 (75°)", "3×1 (71.25°)", "2×1 (63.75°)",
         "1×1 (45° — the balance line)", "1×2 (26.25°)", "1×3 (18.75°)",
         "1×4 (15°)", "1×8 (7.5°)"};

    private static String describeGannFan(String type, List<ValidationInput.Point> pts) {
        String anchor = (pts != null && !pts.isEmpty())
            ? String.format("\n  Anchor: %.2f @ %s", pts.get(0).getPrice(), ts(pts.get(0).getTimestamp()))
            : "";
        return anchor +
            "\n  Fan lines at standard Gann angles: " + String.join(", ", GANN_ANGLES) +
            "\n  Note: exact price per angle depends on the trader's chosen time/price scale.";
    }

    // ── Gann Square ───────────────────────────────────────────────────────────

    private static String describeGannSquare(String type, List<ValidationInput.Point> pts) {
        if (pts == null || pts.size() < 2) return rawPoints(pts);
        ValidationInput.Point p0 = pts.get(0), p1 = pts.get(pts.size() - 1);
        return String.format(
            "\n  Origin: %.2f @ %s  Corner: %.2f @ %s  Range: %.2f price units" +
            "\n  (Grid divides price and time into equal Gann square segments)",
            p0.getPrice(), ts(p0.getTimestamp()), p1.getPrice(), ts(p1.getTimestamp()),
            Math.abs(p1.getPrice() - p0.getPrice()));
    }

    // ── Elliott Wave ──────────────────────────────────────────────────────────

    private static final String[] WAVE_LABELS = {"1","2","3","4","5","A","B","C","D","E"};

    private static String describeElliott(String type, List<ValidationInput.Point> pts) {
        if (pts == null || pts.isEmpty()) return " (no points)";
        StringBuilder sb = new StringBuilder("\n  Wave vertices:");
        for (int i = 0; i < pts.size(); i++) {
            String lbl = i < WAVE_LABELS.length ? WAVE_LABELS[i] : String.valueOf(i + 1);
            sb.append(String.format("\n    Wave %s: %.2f @ %s",
                lbl, pts.get(i).getPrice(), ts(pts.get(i).getTimestamp())));
        }
        return sb.toString();
    }

    // ── Harmonic patterns (XABCD) ─────────────────────────────────────────────

    private static final String[] HARMONIC_LABELS = {"X","A","B","C","D"};

    private static String describeHarmonic(String type, List<ValidationInput.Point> pts) {
        if (pts == null || pts.isEmpty()) return " (no points)";
        StringBuilder sb = new StringBuilder("\n  Pattern legs:");
        for (int i = 0; i < pts.size(); i++) {
            String lbl = i < HARMONIC_LABELS.length ? HARMONIC_LABELS[i] : String.valueOf(i);
            sb.append(String.format("\n    %s: %.2f @ %s",
                lbl, pts.get(i).getPrice(), ts(pts.get(i).getTimestamp())));
        }
        // Append leg ratios so AI can identify the specific harmonic
        if (pts.size() >= 5) {
            double xa = Math.abs(pts.get(1).getPrice() - pts.get(0).getPrice());
            double ab = Math.abs(pts.get(2).getPrice() - pts.get(1).getPrice());
            double bc = Math.abs(pts.get(3).getPrice() - pts.get(2).getPrice());
            double cd = Math.abs(pts.get(4).getPrice() - pts.get(3).getPrice());
            if (xa > 0)
                sb.append(String.format("\n  Ratios — AB/XA: %.3f  BC/AB: %.3f  CD/BC: %.3f",
                    ab / xa, ab > 0 ? bc / ab : 0, bc > 0 ? cd / bc : 0));
        }
        return sb.toString();
    }

    // ── Channel / Regression ──────────────────────────────────────────────────

    private static String describeChannel(String type, List<ValidationInput.Point> pts) {
        if (pts == null || pts.size() < 2) return rawPoints(pts);
        ValidationInput.Point p0 = pts.get(0), p1 = pts.get(1);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("\n  Upper line: %.2f @ %s → %.2f @ %s",
            p0.getPrice(), ts(p0.getTimestamp()), p1.getPrice(), ts(p1.getTimestamp())));
        if (pts.size() >= 3) {
            double width = Math.abs(pts.get(2).getPrice() - p0.getPrice());
            sb.append(String.format("\n  Width: %.2f  Lower line: %.2f → %.2f",
                width, p0.getPrice() - width, p1.getPrice() - width));
        }
        return sb.toString();
    }

    // ── Generic fallback ──────────────────────────────────────────────────────

    private static String describeGeneric(String type, List<ValidationInput.Point> pts) {
        return rawPoints(pts);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    /** Formats epoch seconds as a human-readable IST datetime. */
    static String ts(long epochSeconds) {
        if (epochSeconds == 0) return "n/a";
        return DT_FMT.format(Instant.ofEpochSecond(epochSeconds));
    }

    private static String rawPoints(List<ValidationInput.Point> pts) {
        if (pts == null || pts.isEmpty()) return "";
        if (pts.size() == 1)
            return String.format(" at %.2f @ %s", pts.get(0).getPrice(), ts(pts.get(0).getTimestamp()));
        ValidationInput.Point p0 = pts.get(0), p1 = pts.get(pts.size() - 1);
        String s = String.format(" from %.2f @ %s to %.2f @ %s",
            p0.getPrice(), ts(p0.getTimestamp()), p1.getPrice(), ts(p1.getTimestamp()));
        return pts.size() > 2 ? s + String.format(" (+ %d intermediate points)", pts.size() - 2) : s;
    }

    private static void appendLabel(StringBuilder sb, ValidationInput.Drawing d) {
        if (d.getProperties() != null
            && d.getProperties().getLabel() != null
            && !d.getProperties().getLabel().isBlank()) {
            sb.append(" [").append(d.getProperties().getLabel()).append("]");
        }
    }
}
