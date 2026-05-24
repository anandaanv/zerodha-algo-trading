package com.dtech.aitrader.v2.narrative.engine;

import com.dtech.aitrader.v2.narrative.beat.*;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
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

    /** Snapshot-tier indicator names — these get a state-line-only row (other cols dashed). */
    private static final Set<String> SNAPSHOT_INDICATORS = Set.of("ATR", "VWAP", "Ichimoku");

    /**
     * One per-stock-per-TF memory: header line + indicator rows + bottom digest. Each indicator
     * row is pipe-separated:
     * <pre>
     * ind | now | div_live | regime_live | zone_active | extras
     * </pre>
     * Snapshot-tier rows fill only {@code now}; the other 4 cols are dashed.
     *
     * <p>The bottom digest (v2, owner b3ff4ca0) is 2-3 lines of plain-language summary appended
     * after the table: {@code regime: ... / lean: ... / live: ...}. ≤ ~300 chars total.
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
            if (SNAPSHOT_INDICATORS.contains(n.getIndicator())) {
                sb.append(renderSnapshotRow(n));
            } else {
                sb.append(renderIndicatorRow(n));
            }
        }
        sb.append(renderBottomDigest(indicatorNarratives));
        return sb.toString();
    }

    /**
     * Snapshot-tier row — single state-line row with only the {@code now} cell populated. The
     * other 4 narrative columns are dashed (no divergence / regime / zone / extras for snapshot
     * indicators per Narrative Core tier rules).
     */
    public String renderSnapshotRow(Narrative narrative) {
        StringBuilder row = new StringBuilder();
        row.append(narrative.getIndicator()).append("|");
        Beat currently = narrative.getTiers().getPresent().stream()
                .filter(b -> b.getWhat() == BeatVerb.CURRENTLY).findFirst().orElse(null);
        row.append(currently == null ? "-" : condenseCurrently(currently.getNote(), 180));
        row.append("|-|-|-|-\n");
        return row.toString();
    }

    /**
     * Bottom digest: 2-3 line plain-language summary (regime / lean / live), bounded ≤ ~300 chars
     * total. Picks salient facts from a few key indicators:
     * <ul>
     *   <li><b>regime</b>: ADX/Aroon trend strength + direction; EMA stack state if available.</li>
     *   <li><b>lean</b>: counts of live (recent+present) bullish vs bearish divergences across
     *       full-narrative indicators; signs from MACD zero/signal cross.</li>
     *   <li><b>live</b>: any present-tier active episode (squeeze, oversold zone, breakout).</li>
     * </ul>
     * Prefixed with {@code digest:} on a new line; ≤ ~300 chars total target.
     */
    public String renderBottomDigest(List<Narrative> narratives) {
        Narrative adx = byName(narratives, "ADX_DMI");
        Narrative aroon = byName(narratives, "Aroon");
        Narrative ema = byName(narratives, "EMA_Stack");
        Narrative macd = byName(narratives, "MACD");
        Narrative roc = byName(narratives, "ROC");
        Narrative obv = byName(narratives, "OBV");

        // Regime line — direction + strength from ADX; EMA stack state if present.
        String adxRegime = extractAdxRegime(adx);
        String aroonRegime = extractAroonRegime(aroon);
        String emaState = extractEmaState(ema);
        String regimeLine = String.format("regime: ADX=%s, Aroon=%s, EMA=%s",
                adxRegime, aroonRegime, emaState);
        if (regimeLine.length() > 100) regimeLine = regimeLine.substring(0, 99) + "…";

        // Lean line — count live divergences across full-narrative indicators.
        int bullDiv = 0, bearDiv = 0;
        for (Narrative n : narratives) {
            for (Beat b : recentAndPresent(n)) {
                if (b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE
                        && b.getConsequence() != Consequence.FAILED) {
                    if ("bullish".equals(b.getDirection())) bullDiv++;
                    else if ("bearish".equals(b.getDirection())) bearDiv++;
                }
            }
        }
        String macdSide = extractMacdSide(macd);
        String rocSide = extractRocSide(roc);
        String obvGate = extractObvGate(obv);
        String leanDir = bullDiv > bearDiv ? "bullish-leaning"
                : bearDiv > bullDiv ? "bearish-leaning" : "balanced";
        String leanLine = String.format("lean: %s — div bull=%d bear=%d; MACD %s; ROC %s; vol %s",
                leanDir, bullDiv, bearDiv, macdSide, rocSide, obvGate);
        if (leanLine.length() > 130) leanLine = leanLine.substring(0, 129) + "…";

        // Live line — active present-tier episodes (squeeze, zone, breakout). Dedupe by
        // (indicator, zone) so we don't repeat the same zone multiple times for one indicator.
        java.util.LinkedHashSet<String> live = new java.util.LinkedHashSet<>();
        for (Narrative n : narratives) {
            for (Beat b : n.getTiers().getPresent()) {
                if (b.getWhat() == BeatVerb.ENTERED_ZONE && b.getConsequence() != Consequence.FAILED) {
                    String z = extractZoneName(b.getNote() != null ? b.getNote() : "");
                    if (!z.isEmpty()) live.add(n.getIndicator() + ":" + z);
                }
            }
        }
        List<String> liveList = new java.util.ArrayList<>(live);
        String liveLine = liveList.isEmpty() ? "live: no active episode"
                : "live: " + String.join(", ", liveList.subList(0, Math.min(4, liveList.size())));
        if (liveLine.length() > 130) liveLine = liveLine.substring(0, 129) + "…";

        return regimeLine + "\n" + leanLine + "\n" + liveLine + "\n";
    }

    private static Narrative byName(List<Narrative> ns, String name) {
        for (Narrative n : ns) if (name.equals(n.getIndicator())) return n;
        return null;
    }

    private String extractAdxRegime(Narrative adx) {
        if (adx == null) return "?";
        Beat c = adx.getTiers().getPresent().stream()
                .filter(b -> b.getWhat() == BeatVerb.CURRENTLY).findFirst().orElse(null);
        if (c == null || c.getNote() == null) return "?";
        String s = c.getNote();
        // Note format: "ADX=21.62 (transitional), +DI=15.87, -DI=23.54, direction=bearish."
        String regime = "?", dir = "?";
        if (s.contains("(strong_trend)")) regime = "strong";
        else if (s.contains("(range)")) regime = "range";
        else if (s.contains("(transitional)")) regime = "transitional";
        if (s.contains("direction=bullish")) dir = "bull";
        else if (s.contains("direction=bearish")) dir = "bear";
        return regime + "/" + dir;
    }

    private String extractAroonRegime(Narrative aroon) {
        if (aroon == null) return "?";
        Beat c = aroon.getTiers().getPresent().stream()
                .filter(b -> b.getWhat() == BeatVerb.CURRENTLY).findFirst().orElse(null);
        if (c == null || c.getNote() == null) return "?";
        String s = c.getNote();
        if (s.contains("Regime: uptrend")) return "up";
        if (s.contains("Regime: downtrend")) return "down";
        if (s.contains("Regime: consolidation")) return "consol";
        if (s.contains("Regime: transitional")) return "trans";
        return "?";
    }

    private String extractEmaState(Narrative ema) {
        if (ema == null) return "?";
        Beat c = ema.getTiers().getPresent().stream()
                .filter(b -> b.getWhat() == BeatVerb.CURRENTLY).findFirst().orElse(null);
        if (c == null || c.getNote() == null) return "?";
        String s = c.getNote();
        if (s.contains("bullish_stacked")) return "bull-stk";
        if (s.contains("bearish_stacked")) return "bear-stk";
        if (s.contains("tangled")) return "tang";
        return "?";
    }

    private String extractMacdSide(Narrative macd) {
        if (macd == null) return "?";
        Beat c = macd.getTiers().getPresent().stream()
                .filter(b -> b.getWhat() == BeatVerb.CURRENTLY).findFirst().orElse(null);
        if (c == null) return "?";
        // Note: "line=-22.12, signal=-19.13, histogram=-3.00"
        if (c.getNote() == null) return "?";
        // Pull "line=" sign
        int idx = c.getNote().indexOf("line=");
        if (idx < 0) return "?";
        String after = c.getNote().substring(idx + 5).trim();
        return after.startsWith("-") ? "neg" : "pos";
    }

    private String extractRocSide(Narrative roc) {
        if (roc == null) return "?";
        Beat c = roc.getTiers().getPresent().stream()
                .filter(b -> b.getWhat() == BeatVerb.CURRENTLY).findFirst().orElse(null);
        if (c == null) return "?";
        return c.getValue() != null && c.getValue() > 0 ? "+" : "−";
    }

    private String extractObvGate(Narrative obv) {
        if (obv == null) return "?";
        Beat c = obv.getTiers().getPresent().stream()
                .filter(b -> b.getWhat() == BeatVerb.CURRENTLY).findFirst().orElse(null);
        if (c == null || c.getNote() == null) return "?";
        if (c.getNote().contains("confirming")) return "conf";
        if (c.getNote().contains("diverging")) return "div";
        return "amb";
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
        // Humanize large numbers (OBV cumulative volumes etc) so the table stays terse.
        String sign = v < 0 ? "-" : "";
        if (abs >= 1e9) return String.format("%s%.1fB", sign, abs / 1e9);
        if (abs >= 1e6) return String.format("%s%.1fM", sign, abs / 1e6);
        if (abs >= 1e5) return String.format("%s%.0fK", sign, abs / 1e3);
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
