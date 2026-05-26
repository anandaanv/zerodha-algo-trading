package com.dtech.aitrader.v2.rules.scancontext;

import com.dtech.aitrader.v2.rules.AnnotationEntry;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScanContextParser — spec-derived tests.
 *
 * <p><b>Requirement source:</b>
 * <ul>
 *   <li>Bundle format spec ({@code b5ffa13f}, bundle-resolver v2): the scan-context bundle carries
 *       per-TF zigzag pivots as CSV with columns {@code t,price,kind,structure,rsi,macd,
 *       macd_hist,ewo,adx,di_plus,di_minus,ema20,ema50,ema200,bb_pct_b,atr,retracement}, plus a
 *       {@code ## Annotations (intent overlays)} JSON block listing trader notes with weights.</li>
 *   <li>SPEC-005 input section: pivot structure labels ∈ {HH, HL, LH, LL, BOS_HIGH, BOS_LOW,
 *       CHOCH_HIGH, CHOCH_LOW, FIRST}.</li>
 *   <li>Owner directive {@code 75b20b10}: parser must populate {@code pivotsByTf} keyed by
 *       {@code "Week"}, {@code "Day"}, {@code "OneHour"} so Pass-5 can read all three TFs.</li>
 *   <li>Pass-5 wiring fix (this session): col[4] of the pivot CSV is RSI; parser must populate
 *       {@code rsiAtPivot} so divergence detection can read it without a series.</li>
 * </ul>
 *
 * <p>All expected values trace to the bundle format spec or to the blessed {@code dffe1f75}
 * scan-context excerpt — not to whatever the parser happens to produce.
 */
class ScanContextParserTest {

    @Test
    void weight3_annotation_parsed_into_entry() {
        // Spec: each entry in the JSON array becomes one AnnotationEntry with text=note, weight=weight.
        String md = """
                # AI Trader v2 — Scan Context

                ## Annotations (intent overlays)

                ```json
                [ {
                  "id" : "ann-5",
                  "intent" : "NOTE",
                  "note" : "On weekly, the stock appears to be in wave 4C.",
                  "weight" : 3,
                  "drawing_id" : "ZFbFzY"
                } ]
                ```
                """;

        ScanContextParser.ParsedContext ctx = ScanContextParser.parse(md);
        assertEquals(1, ctx.getAnnotations().size());
        AnnotationEntry a = ctx.getAnnotations().get(0);
        assertEquals("On weekly, the stock appears to be in wave 4C.", a.text());
        assertEquals(3, a.weight());
    }

    @Test
    void empty_annotations_block_returns_empty_list() {
        String md = """
                ## Annotations (intent overlays)

                ```json
                []
                ```
                """;
        assertTrue(ScanContextParser.parse(md).getAnnotations().isEmpty());
    }

    @Test
    void missing_annotations_section_returns_empty_list() {
        // No annotations block at all (some bundles ship without one).
        String md = "# Scan Context\n\nSome other content.\n";
        assertTrue(ScanContextParser.parse(md).getAnnotations().isEmpty());
    }

    @Test
    void malformed_annotation_json_returns_empty_list_does_not_throw() {
        // Per parser contract: log + return empty, never throw — a malformed bundle should
        // degrade gracefully, not kill the whole pipeline.
        String md = """
                ## Annotations (intent overlays)

                ```json
                [ { broken json no quotes
                ```
                """;
        assertDoesNotThrow(() -> {
            ScanContextParser.ParsedContext ctx = ScanContextParser.parse(md);
            assertTrue(ctx.getAnnotations().isEmpty());
        });
    }

    @Test
    void wk_pivot_csv_parsed_with_full_column_contract() {
        // Bundle spec column order: t, price, kind, structure, rsi, macd, macd_hist, ewo, adx,
        // di_plus, di_minus, ema20, ema50, ema200, bb_pct_b, atr, retracement.
        // The parser must extract: timestamp (col 0), price (1), kind (2), structure (3),
        // rsi (4 → rsiAtPivot), atr (15 → atrAtPivot).
        String md = """
                ## Week — Zigzag pivots (last 520 bars, 1 pivots)

                ```csv
                t,price,kind,structure,rsi,macd,macd_hist,ewo,adx,di_plus,di_minus,ema20,ema50,ema200,bb_pct_b,atr,retracement
                2025-12-31T00:00:00Z,1611.8,HIGH,HH,56.1,40.78,3.968,79.681,24.0,25.1,16.6,1484.17,1428.92,1287.73,0.945,51.43,128.897
                ```
                """;

        ScanContextParser.ParsedContext ctx = ScanContextParser.parse(md);
        Map<String, List<MarketStructurePoint>> byTf = ctx.getPivotsByTf();
        assertTrue(byTf.containsKey("Week"));
        List<MarketStructurePoint> wk = byTf.get("Week");
        assertEquals(1, wk.size());

        MarketStructurePoint p = wk.get(0);
        assertEquals(Instant.parse("2025-12-31T00:00:00Z"), p.getTimestamp());
        assertEquals(1611.8, p.getPrice(), 1e-9);
        assertEquals(PivotType.HIGH, p.getPivotType());
        assertEquals(StructureLabel.HH, p.getStructureLabel());
        assertEquals(56.1, p.getRsiAtPivot(), 1e-9, "col[4] rsi must populate rsiAtPivot");
        assertEquals(51.43, p.getAtrAtPivot(), 1e-9, "col[15] atr must populate atrAtPivot");
    }

    @Test
    void all_structure_labels_parsed() {
        // Spec enum: HH, HL, LH, LL, BOS_HIGH, BOS_LOW, CHOCH_HIGH, CHOCH_LOW, FIRST.
        // The parser must recognize each; unknown labels degrade to FIRST.
        String header = "t,price,kind,structure,rsi,macd,macd_hist,ewo,adx,di_plus,di_minus,ema20,ema50,ema200,bb_pct_b,atr,retracement";
        StringBuilder rows = new StringBuilder();
        String[] labels = {"HH", "HL", "LH", "LL", "BOS_HIGH", "BOS_LOW", "CHOCH_HIGH", "CHOCH_LOW", "FIRST"};
        for (int i = 0; i < labels.length; i++) {
            rows.append(String.format("2024-01-%02dT00:00:00Z,100.0,HIGH,%s,50,0,0,0,20,20,20,100,100,100,0.5,10.0,0%n",
                    i + 1, labels[i]));
        }
        String md = "## Week — Zigzag pivots\n\n```csv\n" + header + "\n" + rows + "```\n";

        List<MarketStructurePoint> wk = ScanContextParser.parse(md).getPivotsByTf().get("Week");
        assertEquals(labels.length, wk.size());
        for (int i = 0; i < labels.length; i++) {
            assertEquals(StructureLabel.valueOf(labels[i]), wk.get(i).getStructureLabel(),
                    "row " + i + " label " + labels[i]);
        }
    }

    @Test
    void all_three_tfs_parsed_independently() {
        // Per owner Q1 (75b20b10): parser must populate pivotsByTf keyed by Week / Day / OneHour.
        String md = """
                ## Week — Zigzag pivots

                ```csv
                t,price,kind,structure,rsi,macd,macd_hist,ewo,adx,di_plus,di_minus,ema20,ema50,ema200,bb_pct_b,atr,retracement
                2025-12-31T00:00:00Z,1611.8,HIGH,HH,55,0,0,0,20,20,20,100,100,100,0.5,50,100
                ```

                ## Day — Zigzag pivots

                ```csv
                t,price,kind,structure,rsi,macd,macd_hist,ewo,adx,di_plus,di_minus,ema20,ema50,ema200,bb_pct_b,atr,retracement
                2026-01-04T00:00:00Z,1611.8,HIGH,HH,60,0,0,0,20,20,20,100,100,100,0.5,30,80
                2026-01-31T00:00:00Z,1335.0,LOW,CHOCH_LOW,25,0,0,0,20,20,20,100,100,100,0.1,40,60
                ```

                ## OneHour — Zigzag pivots

                ```csv
                t,price,kind,structure,rsi,macd,macd_hist,ewo,adx,di_plus,di_minus,ema20,ema50,ema200,bb_pct_b,atr,retracement
                2026-05-05T03:45:00Z,1473.4,HIGH,HH,76,0,0,0,20,20,20,100,100,100,0.5,10,50
                2026-05-08T03:45:00Z,1417.5,LOW,HL,42,0,0,0,20,20,20,100,100,100,0.1,11,30
                2026-05-18T03:45:00Z,1318.7,LOW,LL,24,0,0,0,20,20,20,100,100,100,0.1,10,200
                ```
                """;

        ScanContextParser.ParsedContext ctx = ScanContextParser.parse(md);
        assertEquals(1, ctx.getPivotsByTf().get("Week").size());
        assertEquals(2, ctx.getPivotsByTf().get("Day").size());
        assertEquals(3, ctx.getPivotsByTf().get("OneHour").size());
        // Verify cross-TF independence — the LL on OneHour has price 1318.7 and rsi 24.
        MarketStructurePoint lastHr = ctx.getPivotsByTf().get("OneHour").get(2);
        assertEquals(1318.7, lastHr.getPrice(), 1e-9);
        assertEquals(StructureLabel.LL, lastHr.getStructureLabel());
        assertEquals(24.0, lastHr.getRsiAtPivot(), 1e-9);
    }

    @Test
    void missing_section_for_one_tf_returns_no_entry_for_that_tf() {
        // Bundle ships only Week pivots (e.g. a low-data symbol). Day + Hr keys must not appear.
        String md = """
                ## Week — Zigzag pivots

                ```csv
                t,price,kind,structure,rsi,macd,macd_hist,ewo,adx,di_plus,di_minus,ema20,ema50,ema200,bb_pct_b,atr,retracement
                2025-12-31T00:00:00Z,1611.8,HIGH,HH,55,0,0,0,20,20,20,100,100,100,0.5,50,100
                ```
                """;
        Map<String, List<MarketStructurePoint>> byTf = ScanContextParser.parse(md).getPivotsByTf();
        assertTrue(byTf.containsKey("Week"));
        assertFalse(byTf.containsKey("Day"));
        assertFalse(byTf.containsKey("OneHour"));
    }

    @Test
    void pivot_row_with_unparseable_timestamp_skipped_not_thrown() {
        // Single bad row shouldn't kill the whole pivot list — parser logs and skips.
        String md = """
                ## Week — Zigzag pivots

                ```csv
                t,price,kind,structure,rsi,macd,macd_hist,ewo,adx,di_plus,di_minus,ema20,ema50,ema200,bb_pct_b,atr,retracement
                NOT-A-DATE,1611.8,HIGH,HH,55,0,0,0,20,20,20,100,100,100,0.5,50,100
                2025-12-31T00:00:00Z,1611.8,HIGH,HH,55,0,0,0,20,20,20,100,100,100,0.5,50,100
                ```
                """;
        ScanContextParser.ParsedContext ctx = ScanContextParser.parse(md);
        assertEquals(1, ctx.getPivotsByTf().get("Week").size(),
                "good row parsed, bad row skipped, no exception");
    }

    @Test
    void blank_rsi_column_results_in_null_rsiAtPivot() {
        // Spec implication: per-pivot indicator columns can be empty for the FIRST pivot (no
        // indicators warmed up yet). Parser must treat blank as null, not as 0.0 — otherwise
        // divergence detection would see 0 RSI and produce false signals.
        String md = """
                ## Week — Zigzag pivots

                ```csv
                t,price,kind,structure,rsi,macd,macd_hist,ewo,adx,di_plus,di_minus,ema20,ema50,ema200,bb_pct_b,atr,retracement
                2016-09-22T00:00:00Z,269.2,HIGH,FIRST,,,,,,,,,,,,10.54,
                ```
                """;
        MarketStructurePoint p = ScanContextParser.parse(md).getPivotsByTf().get("Week").get(0);
        assertEquals(269.2, p.getPrice(), 1e-9);
        assertEquals(StructureLabel.FIRST, p.getStructureLabel());
        assertNull(p.getRsiAtPivot(),
                "blank rsi column must produce null rsiAtPivot, not 0.0");
        assertEquals(10.54, p.getAtrAtPivot(), 1e-9);
    }

    @Test
    void unknown_structure_label_degrades_to_FIRST() {
        // Defensive: a future bundle with a new label (e.g. "BLAH") must not crash; parser
        // assigns FIRST and lets downstream rules treat as ambiguous.
        String md = """
                ## Week — Zigzag pivots

                ```csv
                t,price,kind,structure,rsi,macd,macd_hist,ewo,adx,di_plus,di_minus,ema20,ema50,ema200,bb_pct_b,atr,retracement
                2025-12-31T00:00:00Z,1611.8,HIGH,UNKNOWN_LABEL,55,0,0,0,20,20,20,100,100,100,0.5,50,100
                ```
                """;
        MarketStructurePoint p = ScanContextParser.parse(md).getPivotsByTf().get("Week").get(0);
        assertEquals(StructureLabel.FIRST, p.getStructureLabel(),
                "unknown label degrades to FIRST per parser contract");
    }

    @Test
    void low_kind_parses_correctly() {
        // Symmetric coverage to the HIGH cases. The parser key check: case-insensitive "HIGH"
        // → HIGH, anything else → LOW.
        String md = """
                ## Week — Zigzag pivots

                ```csv
                t,price,kind,structure,rsi,macd,macd_hist,ewo,adx,di_plus,di_minus,ema20,ema50,ema200,bb_pct_b,atr,retracement
                2026-04-01T00:00:00Z,1290.0,LOW,LL,36.7,-23.8,-16.8,-61.7,22.3,10.9,27.6,1415,1415,1301,0.086,70.24,129.126
                ```
                """;
        MarketStructurePoint p = ScanContextParser.parse(md).getPivotsByTf().get("Week").get(0);
        assertEquals(PivotType.LOW, p.getPivotType());
        assertEquals(1290.0, p.getPrice(), 1e-9);
        assertEquals(36.7, p.getRsiAtPivot(), 1e-9);
    }

    @Test
    void null_input_returns_empty_context_no_throw() {
        ScanContextParser.ParsedContext ctx = ScanContextParser.parse(null);
        assertTrue(ctx.getAnnotations().isEmpty());
        assertTrue(ctx.getPivotsByTf().isEmpty());
    }

    @Test
    void blank_input_returns_empty_context() {
        ScanContextParser.ParsedContext ctx = ScanContextParser.parse("   \n  \t \n");
        assertTrue(ctx.getAnnotations().isEmpty());
        assertTrue(ctx.getPivotsByTf().isEmpty());
    }
}
