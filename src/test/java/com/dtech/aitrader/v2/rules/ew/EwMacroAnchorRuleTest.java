package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pass-1 EwMacroAnchorRule — locks anchor selection against the blessed RELIANCE EW reference
 * (`cde6bbc9`). The deterministic algorithm: find the (highest HIGH, lowest LOW) pair in the
 * lookback window and anchor on whichever came first chronologically (corrective from H if
 * H precedes L; impulsive from L if L precedes H).
 *
 * <p>RELIANCE target (criterion a from cde6bbc9): anchor = 1611.8 @ 2025-12-31, with the implied
 * current structure being CORRECTIVE (down from 1611 to 1290).
 */
class EwMacroAnchorRuleTest {

    private static final EwMacroAnchorRule RULE = new EwMacroAnchorRule();

    @Test
    void anchor_matches_blessed_RELIANCE_reference() {
        SymbolContext ctx = relianceWeeklyFixture();
        List<Firing> emitted = RULE.evaluate(ctx, List.of());
        assertEquals(1, emitted.size(), "exactly one anchor FACT");

        Firing anchor = emitted.get(0);
        assertEquals(EwMacroAnchorRule.RULE_ID, anchor.getRuleId());
        assertEquals(Family.EW, anchor.getFamily());
        assertEquals(Pass.P1_STRUCTURAL, anchor.getPass());
        assertEquals(FiresOn.FACT, anchor.getFiresOn());

        // criterion a: anchor = 1611.8 @ 2025-12-31
        assertEquals(1611.8, (double) anchor.getPayload().get("anchor_price"), 0.01,
                "anchor price must be the blessed 1611.8");
        assertEquals("2025-12-31", anchor.getPayload().get("anchor_date"),
                "anchor date must be the blessed 2025-12-31");
        assertEquals("HIGH", anchor.getPayload().get("anchor_kind"),
                "anchor is a HIGH pivot (the corrective structure starts at the swing high)");
        assertEquals("corrective", anchor.getPayload().get("role_candidate"),
                "structure is corrective — high precedes low");

        // The magnitude of the current structure (1611.8 - 1290 = 321.8)
        double magnitude = (double) anchor.getPayload().get("magnitude_pts");
        assertEquals(321.8, magnitude, 0.5, "magnitude = blessed swing 1611.8 - 1290");
    }

    @Test
    void emits_data_insufficient_fact_when_too_few_pivots() {
        // Less than 6 Wk pivots → engine should not anchor.
        SymbolContext ctx = SymbolContext.builder()
                .symbol("TEST")
                .tf("Week")
                .asOf(LocalDate.of(2026, 5, 22))
                .pivots(List.of(
                        pivot(2024, 1, 1, PivotType.HIGH, 100.0, StructureLabel.FIRST),
                        pivot(2024, 2, 1, PivotType.LOW, 90.0, StructureLabel.LL),
                        pivot(2024, 3, 1, PivotType.HIGH, 105.0, StructureLabel.HH)
                ))
                .build();

        List<Firing> emitted = RULE.evaluate(ctx, List.of());
        assertEquals(1, emitted.size(), "must emit one FACT signaling data_sufficient=false");
        assertEquals(Boolean.FALSE, emitted.get(0).getPayload().get("data_sufficient"));
    }

    @Test
    void impulsive_when_low_is_more_recent_than_high() {
        // Fixture where the absolute min LOW comes AFTER the absolute max HIGH:
        // bear market down to a final low, then a small rally starting from it.
        // The current structure is the impulsive rally starting from that LOW.
        List<MarketStructurePoint> pivots = new ArrayList<>();
        pivots.add(pivot(2024, 1,  1, PivotType.HIGH, 150.0, StructureLabel.FIRST));  // abs max
        pivots.add(pivot(2024, 3,  1, PivotType.LOW,   80.0, StructureLabel.LL));
        pivots.add(pivot(2024, 5,  1, PivotType.HIGH, 120.0, StructureLabel.LH));
        pivots.add(pivot(2024, 7,  1, PivotType.LOW,   60.0, StructureLabel.LL));    // abs min — more recent than abs max
        pivots.add(pivot(2024, 9,  1, PivotType.HIGH,  90.0, StructureLabel.LH));
        pivots.add(pivot(2024, 11, 1, PivotType.LOW,   75.0, StructureLabel.HL));
        pivots.add(pivot(2024, 12, 1, PivotType.HIGH, 100.0, StructureLabel.HH));

        SymbolContext ctx = SymbolContext.builder()
                .symbol("UP").tf("Week").asOf(LocalDate.of(2024, 12, 15))
                .pivots(pivots).build();
        List<Firing> emitted = RULE.evaluate(ctx, List.of());
        assertEquals(1, emitted.size());
        Firing anchor = emitted.get(0);
        assertEquals("impulsive", anchor.getPayload().get("role_candidate"));
        assertEquals(60.0, (double) anchor.getPayload().get("anchor_price"), 0.001);
        assertEquals("LOW", anchor.getPayload().get("anchor_kind"));
        assertEquals("2024-07-01", anchor.getPayload().get("anchor_date"));
    }

    // ── fixture ────────────────────────────────────────────────────────────────

    /**
     * RELIANCE Wk pivot subset from the blessed reference cde6bbc9 — covering the recent
     * corrective structure plus older history for cluster scans (the 1361 + 1290-1307 + 1473-1489
     * touches the rule needs across years).
     */
    private static SymbolContext relianceWeeklyFixture() {
        List<MarketStructurePoint> pivots = new ArrayList<>();

        // Older history feeding cluster scan (2022-2024)
        pivots.add(pivot(2022, 4, 27, PivotType.HIGH, 1361.25, StructureLabel.HH));
        pivots.add(pivot(2022, 8, 15, PivotType.LOW,  1200.0,  StructureLabel.LL));
        pivots.add(pivot(2023, 7, 19, PivotType.HIGH, 1361.20, StructureLabel.LH));
        pivots.add(pivot(2024, 5, 29, PivotType.HIGH, 1359.30, StructureLabel.LH));
        pivots.add(pivot(2024, 6, 24, PivotType.HIGH, 1460.00, StructureLabel.HH));
        pivots.add(pivot(2024, 7, 22, PivotType.HIGH, 1512.00, StructureLabel.HH));
        pivots.add(pivot(2025, 3, 19, PivotType.LOW,  1307.70, StructureLabel.LL));

        // The blessed corrective structure
        pivots.add(pivot(2025, 12, 31, PivotType.HIGH, 1611.80, StructureLabel.HH));        // BLESSED anchor
        pivots.add(pivot(2026,  1, 28, PivotType.HIGH, 1489.50, StructureLabel.LH));        // earlier high in resistance cluster
        pivots.add(pivot(2026,  1, 28, PivotType.LOW,  1335.00, StructureLabel.CHOCH_LOW)); // character change
        pivots.add(pivot(2026,  4,  1, PivotType.LOW,  1290.00, StructureLabel.LL));        // BLESSED low — bottom of corrective
        pivots.add(pivot(2026,  4, 29, PivotType.HIGH, 1473.40, StructureLabel.LH));        // failed bounce (B-wave per blessed)

        return SymbolContext.builder()
                .symbol("RELIANCE")
                .tf("Week")
                .asOf(LocalDate.of(2026, 5, 22))
                .pivots(pivots)
                .build();
    }

    private static MarketStructurePoint pivot(int y, int m, int d, PivotType type, double price,
                                                StructureLabel label) {
        return MarketStructurePoint.builder()
                .pivotType(type)
                .structureLabel(label)
                .timestamp(LocalDate.of(y, m, d).atStartOfDay().toInstant(ZoneOffset.UTC))
                .price(price)
                .atrAtPivot(50.0)
                .build();
    }
}
