package com.dtech.aitrader.v2.narrative.engine;

import com.dtech.aitrader.v2.narrative.beat.*;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Table-style signal-only rendering for the multi-TF run. Each per-stock-per-TF memory is a
 * single table: one header row, one row per indicator, fixed columns. Variable-length structured
 * data (divergence pivot pairs, regime details) is packed into cells with terse field markers
 * (b=bar, v=value, px=price, cnf=confirmed, fail=failed).
 *
 * <p>Target ~1-1.5KB per memory. Saves tokens vs the prior key=value-lines format by:
 * <ul>
 *   <li>Removing repeated indicator labels (header lists them once)</li>
 *   <li>Single-line-per-indicator (~120-200 chars vs the prior 6-10 lines)</li>
 *   <li>Pipe separator tokenizes denser than punctuation-rich key=value lines</li>
 * </ul>
 *
 * <p>Per the governing principle: confirmed-and-in-force structural signals (live divergences,
 * latest regime, active zone, failure swings) are kept verbatim. History-tier beat enumeration
 * and prose are dropped. The LLM consumer reads the indicator row, infers structure from the
 * pipe-separated columns.
 */
public class CompactNarrativeRenderer {

    /**
     * One per-stock-per-TF memory: header line + 6 indicator rows. Each row format:
     * <pre>
     * ind | now | div_live | regime_live | zone_active | extras
     * </pre>
     */
    public String renderMtfMemory(String symbol, String timeframe, String bar0Date, int barCount,
                                   int lastIdx, String lastDate, double lastClose,
                                   List<Narrative> indicatorNarratives) {
        StringBuilder sb = new StringBuilder();
        sb.append(symbol).append(" ").append(timeframe)
                .append(" bar0=").append(bar0Date)
                .append(" last=b").append(lastIdx).append("@").append(lastDate)
                .append(" close=").append(fmt(lastClose))
                .append(" bars=").append(barCount).append("\n");
        sb.append("ind|now|div_live|regime_live|zone|extras\n");
        for (Narrative n : indicatorNarratives) {
            sb.append(renderIndicatorRow(n));
        }
        return sb.toString();
    }

    /**
     * One indicator row, pipe-separated. Empty cells get "-".
     */
    public String renderIndicatorRow(Narrative narrative) {
        StringBuilder row = new StringBuilder();
        row.append(narrative.getIndicator()).append("|");

        // Column 1: currently posture (condensed)
        Beat currently = narrative.getTiers().getPresent().stream()
                .filter(b -> b.getWhat() == BeatVerb.CURRENTLY).findFirst().orElse(null);
        row.append(currently == null ? "-" : condenseCurrently(currently.getNote(), 140)).append("|");

        // Column 2: live divergences (recent+present), packed
        List<Beat> divs = recentAndPresent(narrative).stream()
                .filter(b -> b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE)
                .collect(Collectors.toList());
        // Also keep failure-swings (FAILED_ATTEMPT with type=failure_swing) as structural
        List<Beat> fs = recentAndPresent(narrative).stream()
                .filter(b -> b.getWhat() == BeatVerb.FAILED_ATTEMPT
                        && fs1(b.getType()))
                .collect(Collectors.toList());
        if (divs.isEmpty() && fs.isEmpty()) row.append("-");
        else {
            for (int i = 0; i < divs.size(); i++) {
                if (i > 0) row.append(";");
                Beat d = divs.get(i);
                row.append(shortDir(d.getDirection())).append(" ")
                        .append(consShort(d.getConsequence())).append("@b").append(d.getWhenBar());
                if (d.getPivotPair() != null && d.getPivotPair().size() >= 2) {
                    PivotPairEntry p1 = d.getPivotPair().get(0);
                    PivotPairEntry p2 = d.getPivotPair().get(1);
                    row.append("(b").append(p1.getBar()).append("v").append(fmt(p1.getMacd()))
                            .append("/b").append(p2.getBar()).append("v").append(fmt(p2.getMacd())).append(")");
                }
                if (d.getNote() != null && d.getNote().contains("INVALIDATED")) {
                    String invBar = extractInvBar(d.getNote());
                    if (!invBar.isEmpty()) row.append(" INV@b").append(invBar);
                }
            }
            for (int i = 0; i < fs.size(); i++) {
                if (i > 0 || !divs.isEmpty()) row.append(";");
                Beat f = fs.get(i);
                row.append("fs ").append(shortDir(f.getDirection())).append("@b").append(f.getWhenBar());
            }
        }
        row.append("|");

        // Column 3: latest in-force regime_change
        Beat latestRegime = recentAndPresent(narrative).stream()
                .filter(b -> b.getWhat() == BeatVerb.REGIME_CHANGE)
                .max(Comparator.comparingInt(Beat::getWhenBar)).orElse(null);
        if (latestRegime == null) row.append("-");
        else {
            row.append(shortDir(latestRegime.getDirection()));
            if (latestRegime.getType() != null) row.append(" ").append(latestRegime.getType());
            row.append("@b").append(latestRegime.getWhenBar());
            if (latestRegime.getPersistedBars() != null) row.append("/").append(latestRegime.getPersistedBars()).append("b");
        }
        row.append("|");

        // Column 4: active zone (PRESENT-tier entered_zone) and latest exit
        Beat presentEntry = narrative.getTiers().getPresent().stream()
                .filter(b -> b.getWhat() == BeatVerb.ENTERED_ZONE)
                .max(Comparator.comparingInt(Beat::getWhenBar)).orElse(null);
        Beat recentExit = recentAndPresent(narrative).stream()
                .filter(b -> b.getWhat() == BeatVerb.EXITED_ZONE)
                .max(Comparator.comparingInt(Beat::getWhenBar)).orElse(null);
        if (presentEntry == null && recentExit == null) row.append("-");
        else {
            if (presentEntry != null) {
                row.append("in ").append(extractZoneName(presentEntry.getNote()))
                        .append("@b").append(presentEntry.getWhenBar());
                if (presentEntry.getPersistedBars() != null) row.append("/").append(presentEntry.getPersistedBars()).append("b");
            }
            if (recentExit != null && (presentEntry == null || recentExit.getWhenBar() > presentEntry.getWhenBar())) {
                if (presentEntry != null) row.append(";");
                row.append("out ").append(extractZoneName(recentExit.getNote()))
                        .append("@b").append(recentExit.getWhenBar());
                if (recentExit.getPersistedBars() != null) row.append("/").append(recentExit.getPersistedBars()).append("b");
            }
        }
        row.append("|");

        // Column 5: extras (high-sig CROSSED in recent+present, top pk/tr, fa count)
        StringBuilder extras = new StringBuilder();
        List<Beat> hiCross = recentAndPresent(narrative).stream()
                .filter(b -> b.getWhat() == BeatVerb.CROSSED
                        && b.getSignificance() != null && b.getSignificance() >= 0.8)
                .collect(Collectors.toList());
        for (Beat c : hiCross) {
            if (extras.length() > 0) extras.append(";");
            extras.append("x ").append(shortDir(c.getDirection()));
            if (c.getType() != null) extras.append(" ").append(c.getType());
            extras.append("@b").append(c.getWhenBar());
        }
        // Top recent peak + trough
        narrative.getTiers().getRecent().stream()
                .filter(b -> b.getWhat() == BeatVerb.PEAKED)
                .max(Comparator.comparingDouble(b -> b.getSignificance() == null ? 0.0 : b.getSignificance()))
                .ifPresent(p -> {
                    if (extras.length() > 0) extras.append(";");
                    extras.append("pk@b").append(p.getWhenBar()).append("v").append(fmt(p.getValue()));
                });
        narrative.getTiers().getRecent().stream()
                .filter(b -> b.getWhat() == BeatVerb.TROUGHED)
                .max(Comparator.comparingDouble(b -> b.getSignificance() == null ? 0.0 : b.getSignificance()))
                .ifPresent(t -> {
                    if (extras.length() > 0) extras.append(";");
                    extras.append("tr@b").append(t.getWhenBar()).append("v").append(fmt(t.getValue()));
                });
        long fa = recentAndPresent(narrative).stream()
                .filter(b -> b.getWhat() == BeatVerb.FAILED_ATTEMPT)
                .filter(b -> !fs1(b.getType()))
                .count();
        if (fa > 0) {
            if (extras.length() > 0) extras.append(";");
            extras.append("fa=").append(fa);
        }
        row.append(extras.length() == 0 ? "-" : extras.toString());
        row.append("\n");
        return row.toString();
    }

    // ===== helpers =====

    private boolean fs1(String type) {
        return type != null && type.equals("failure_swing");
    }

    private List<Beat> recentAndPresent(Narrative n) {
        return Stream.concat(n.getTiers().getRecent().stream(),
                             n.getTiers().getPresent().stream())
                .collect(Collectors.toList());
    }

    private String shortDir(String dir) {
        if (dir == null) return "";
        switch (dir) {
            case "bullish": return "bull";
            case "bearish": return "bear";
            case "tangled": return "tang";
            default: return dir.length() > 4 ? dir.substring(0, 4) : dir;
        }
    }

    private String consShort(Consequence c) {
        if (c == null) return "?";
        switch (c) {
            case CONFIRMED: return "cnf";
            case FAILED:    return "fail";
            case ONGOING:   return "ong";
            default: return c.getJsonValue();
        }
    }

    private String fmt(Double v) {
        if (v == null) return "?";
        double abs = Math.abs(v);
        if (abs >= 1000) return String.format("%.0f", v);
        if (abs >= 10)   return String.format("%.1f", v);
        return String.format("%.2f", v);
    }

    private String condenseCurrently(String note, int maxLen) {
        if (note == null) return "-";
        String s = note;
        int idx = s.indexOf("at last bar:");
        if (idx >= 0) s = s.substring(idx + "at last bar:".length()).trim();
        // Drop downstream-conditioner explanation
        int condIdx = s.indexOf("Conditioner:");
        if (condIdx >= 0) s = s.substring(0, condIdx).trim();
        // Drop trailing parentheticals after the main posture
        int parenIdx = s.lastIndexOf("(");
        if (parenIdx > 0 && s.indexOf(')', parenIdx) == s.length() - 1) {
            String tail = s.substring(parenIdx);
            // Drop param-style parentheticals like "(os_upper=30.0, ob_lower=70.0)"
            if (tail.contains("=") && tail.contains(",")) s = s.substring(0, parenIdx).trim();
        }
        if (s.endsWith(".") || s.endsWith(",")) s = s.substring(0, s.length() - 1);
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > maxLen) s = s.substring(0, maxLen - 1) + "…";
        return s;
    }

    private String extractZoneName(String note) {
        if (note == null) return "";
        String s = note;
        if (s.startsWith("Entered ")) s = s.substring(8);
        else if (s.startsWith("Exited ")) s = s.substring(7);
        int paren = s.indexOf("(");
        if (paren >= 0) s = s.substring(0, paren).trim();
        int dash = s.indexOf(" —");
        if (dash >= 0) s = s.substring(0, dash).trim();
        s = s.replaceAll(" after \\d+ bars?", "");
        if (s.length() > 20) s = s.substring(0, 20);
        return s.replace(" ", "-").replace("---", "-");
    }

    private String extractInvBar(String note) {
        int idx = note.indexOf("INVALIDATED at bar ");
        if (idx < 0) return "";
        int start = idx + "INVALIDATED at bar ".length();
        StringBuilder digits = new StringBuilder();
        for (int i = start; i < note.length() && i < start + 6; i++) {
            char c = note.charAt(i);
            if (Character.isDigit(c)) digits.append(c);
            else break;
        }
        return digits.toString();
    }
}
