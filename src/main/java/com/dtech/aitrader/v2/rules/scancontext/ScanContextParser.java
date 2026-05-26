package com.dtech.aitrader.v2.rules.scancontext;

import com.dtech.aitrader.v2.rules.AnnotationEntry;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Markdown body of an {@code ai-trader-scan-context} memsys memory (e.g.
 * {@code dffe1f75}) into structured fields: per-TF pivot lists with structure labels + indicator
 * values stamped at each pivot, plus trader annotations.
 *
 * <p>Scan-context format (per the v2 bundle resolver {@code b5ffa13f}):
 * <pre>
 * ## Annotations (intent overlays)
 * ```json
 * [ { "id", "intent", "note", "weight", "drawing_id", "interval", "params", "geometry" }, ... ]
 * ```
 *
 * ## Week — Zigzag pivots (last 520 bars, 57 pivots)
 * ```csv
 * t,price,kind,structure,rsi,macd,macd_hist,ewo,adx,di_plus,di_minus,ema20,ema50,ema200,bb_pct_b,atr,retracement
 * 2016-09-22T00:00:00Z,269.2,HIGH,FIRST,...
 * ```
 *
 * ## Day — Zigzag pivots ...
 * ## OneHour — Zigzag pivots ...
 * </pre>
 *
 * <p>This parser is deterministic; no LLM involved. Tested against the real RELIANCE
 * {@code dffe1f75} bundle.
 */
@Slf4j
public final class ScanContextParser {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** Header → TF label mapping. */
    private static final Map<String, String> TF_HEADERS = Map.of(
            "Week", "Week",
            "Day", "Day",
            "OneHour", "OneHour"
    );

    private static final Pattern ANNOTATIONS_BLOCK =
            Pattern.compile("## Annotations[^\\n]*\\n+```json\\s*\\n(.+?)\\n```",
                    Pattern.DOTALL);

    private ScanContextParser() {}

    public static ParsedContext parse(String markdownBody) {
        if (markdownBody == null || markdownBody.isBlank()) {
            return new ParsedContext(List.of(), Map.of());
        }
        List<AnnotationEntry> annotations = parseAnnotations(markdownBody);
        Map<String, List<MarketStructurePoint>> pivotsByTf = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : TF_HEADERS.entrySet()) {
            List<MarketStructurePoint> pivots = parsePivotCsv(markdownBody, e.getKey());
            if (!pivots.isEmpty()) pivotsByTf.put(e.getValue(), pivots);
        }
        return new ParsedContext(annotations, pivotsByTf);
    }

    // ── annotations ────────────────────────────────────────────────────────────

    private static List<AnnotationEntry> parseAnnotations(String md) {
        Matcher m = ANNOTATIONS_BLOCK.matcher(md);
        if (!m.find()) return List.of();
        String jsonBlock = m.group(1).trim();
        if (jsonBlock.isEmpty() || jsonBlock.equals("[]")) return List.of();
        try {
            JsonNode arr = JSON.readTree(jsonBlock);
            if (!arr.isArray()) return List.of();
            List<AnnotationEntry> out = new ArrayList<>();
            for (JsonNode node : arr) {
                String text = node.has("note") ? node.get("note").asText("") : "";
                int weight = node.has("weight") ? node.get("weight").asInt(0) : 0;
                // The scan-context doesn't carry a timestamp per annotation; use null (consumer
                // treats annotation as "current" at as_of).
                out.add(new AnnotationEntry(text, weight, null, null));
            }
            return out;
        } catch (Exception e) {
            log.warn("[scan-context] annotation parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── pivot CSV per TF ───────────────────────────────────────────────────────

    /**
     * Find the {@code ## <Tf> — Zigzag pivots ...} section and parse its CSV block into
     * {@link MarketStructurePoint}s.
     */
    private static List<MarketStructurePoint> parsePivotCsv(String md, String tfHeader) {
        // Section markers vary slightly; tolerate the em-dash or " - " separator.
        Pattern section = Pattern.compile(
                "## " + Pattern.quote(tfHeader) + "\\s+[—\\-]\\s+Zigzag pivots[^\\n]*\\n+```csv\\s*\\n(.+?)\\n```",
                Pattern.DOTALL);
        Matcher m = section.matcher(md);
        if (!m.find()) return List.of();
        String csv = m.group(1);
        String[] lines = csv.split("\\n");
        if (lines.length < 2) return List.of();

        // Header has: t,price,kind,structure,rsi,macd,macd_hist,ewo,adx,di_plus,di_minus,
        //             ema20,ema50,ema200,bb_pct_b,atr,retracement
        List<MarketStructurePoint> pivots = new ArrayList<>(lines.length);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] cols = line.split(",", -1);
            if (cols.length < 4) continue;
            try {
                Instant t = Instant.parse(cols[0]);
                double price = Double.parseDouble(cols[1]);
                PivotType kind = "HIGH".equalsIgnoreCase(cols[2]) ? PivotType.HIGH : PivotType.LOW;
                StructureLabel label = parseLabel(cols[3]);
                double atr = cols.length > 15 ? safeDouble(cols[15]) : 0.0;
                Double rsi = cols.length > 4 ? safeNullableDouble(cols[4]) : null;
                pivots.add(MarketStructurePoint.builder()
                        .pivotType(kind)
                        .structureLabel(label)
                        .timestamp(t)
                        .price(price)
                        .atrAtPivot(atr)
                        .rsiAtPivot(rsi)
                        .build());
            } catch (Exception e) {
                log.warn("[scan-context] pivot row parse failed tf={} line='{}': {}",
                        tfHeader, line, e.getMessage());
            }
        }
        return pivots;
    }

    private static StructureLabel parseLabel(String s) {
        if (s == null || s.isBlank()) return StructureLabel.FIRST;
        try { return StructureLabel.valueOf(s.trim()); }
        catch (IllegalArgumentException e) { return StructureLabel.FIRST; }
    }

    private static double safeDouble(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0.0; }
    }

    private static Double safeNullableDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    /** Parsed scan-context bundle. */
    @Value
    public static class ParsedContext {
        List<AnnotationEntry> annotations;
        Map<String, List<MarketStructurePoint>> pivotsByTf;
    }
}
