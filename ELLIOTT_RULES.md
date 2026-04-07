# Elliott Wave Rules — Implementation Documentation

This document details all Elliott Wave rules as implemented in the codebase, suitable for expert review.

---

## Overview

The engine is a 9-layer system. Key classes:
- `WaveCounter.java` — detects and scores wave counts (impulse, corrective, triangle)
- `ScenarioBuilder.java` — groups wave counts into directional scenarios and hypotheses
- `ElliottWaveAnalyzer.java` — orchestrates all layers, applies segment proportionality bonus, bottom-up cross-TF boosts
- `NestedCorrectiveContextBuilder.java` — builds nested W4 corrective branch hypotheses
- `VirginTrendlineDetector.java` — detects virgin trendlines from impulse wave pivots

---

## Scoring System

Each `WaveCount` has five score components (fields on `WaveCount.java`):
- `fibonacciScore` — 0–40: Fibonacci ratio adherence
- `indicatorScore` — 0–30 (impulse), 0–15 (corrective/triangle): indicator behavior
- `crossTfScore` — 0–20: lower-TF internal structure + parent TF alignment
- `alternationScore` — 0–10: W2/W4 alternation
- `proportionalityBonus` — 0–15: segment proportionality (applied post-processing in ElliottWaveAnalyzer)
- `totalScore()` = sum of all five

---

## Fibonacci Constants (WaveCounter)

```
FIB_TOLERANCE      = 0.15   (±15% tolerance on all ratio checks)
W2_IDEAL_RETRACE   = 0.618  (W2 retraces 61.8% of W1)
W3_IDEAL_EXTENSION = 1.618  (W3 extends 161.8% of W1)
W4_IDEAL_RETRACE   = 0.382  (W4 retraces 38.2% of W3)
W5_IDEAL_EXTENSION = 1.0    (W5 equals W1 in length)
B_ZIGZAG_MAX       = 0.786  (Zigzag B retraces max 78.6% of A)
B_FLAT_MIN         = 0.900  (Flat B retraces min 90% of A)
C_ZIGZAG_MIN       = 0.618  (C retraces min 61.8% of A)
C_ZIGZAG_MAX       = 1.618  (C extends max 161.8% of A)
```

---

## Hard Rules (return null / add ruleViolation)

### Impulse (upward)

| Rule | Implementation |
|------|---------------|
| W1 must be positive (price increase) | Returns null if W1 end ≤ W1 start |
| W2 cannot retrace 100% or more of W1 | Returns null if w2Retrace ≥ 1.0 |
| W2 must not break W1 start | Returns null if W2 end ≤ W1 start |
| W3 must exceed W1 end | Returns null if W3 end ≤ W1 end |
| W4 cannot overlap W1 territory (W4 low > W1 high) | Adds to ruleViolations list (count still built, flagged) |
| W3 must not be the shortest wave | Adds to ruleViolations list |

**Note:** W4/W1 overlap builds a wave count with violations rather than null — the count is kept but marked. W3 shortest is also kept with violations. Counts with violations still flow through scoring but are expected to score lower.

### Corrective Type Discrimination (based on Wave B retrace of A)

| B Retrace Range | Type assigned |
|----------------|--------------|
| ≤ 78.6% | ZIGZAG |
| ≥ 90.0% | FLAT |
| > 100% | EXPANDED_FLAT |
| > 100% AND C ends beyond A | RUNNING_FLAT / EXPANDED_FLAT |
| 78.6%–90.0% (ambiguous zone) | Returns null (rejected) |

### Triangle

- Requires alternating highs and lows (min 5 pivots = ABCDE)
- Classifies as: Symmetrical (descending highs + ascending lows), Ascending (flat highs + ascending lows), Descending (descending highs + flat lows)
- Flat threshold: < 1.5% range across highs or lows
- Returns null if neither classification fits

---

## Fibonacci Scoring

### Impulse (max 40 pts)

| Measurement | Ideal | Max Points | Proximity tiers |
|------------|-------|-----------|----------------|
| W2 retrace of W1 | 61.8% | 8 pts | ≤5%→full, ≤10%→70%, ≤15%→40%, >15%→0 |
| W3 / W1 ratio | 161.8% | 16 pts | same tiers |
| W4 retrace of W3 | 38.2% | 8 pts | same tiers |
| W5 / W1 ratio | 100% | 8 pts | same tiers |

### Corrective (max 24 pts)

| Measurement | Ideal (Zigzag) | Ideal (Flat) | Max Points |
|------------|---------------|-------------|-----------|
| B retrace of A | 61.8% | 100% | 12 pts |
| C / A ratio | 100% | 100% | 12 pts |

### Triangle (max 20 pts)

- Scored by convergence quality and internal pivot count (approx 20 pts fibonacciScore)

---

## Indicator Scoring

### Impulse (max ~45 pts)

| Check | Points | Notes |
|-------|--------|-------|
| EWO peaks at W3 (vs W1 and W5) | 0–10 | Magnitude-weighted: `ewoLead * 10` |
| W5 MACD histogram < W3 (divergence) | 0–10 | Magnitude-weighted: `macdDiv * 10` |
| W3 Bollinger Band walk | +7 | Binary |
| W4 stochastic K < 30 (oversold) | +7 | Binary |
| W4 MACD histogram amplitude < 50% of W3 amplitude | +8 | W4 contraction signature |
| W4 MACD near zero | +7 | Consolidation signature |
| W3 relative volume > W1 relative volume | +4 | Momentum confirmation |
| W5 relative volume < W3 relative volume | +4 | Exhaustion confirmation |

**⚠ Review note:** Maximum possible = 57 pts but field cap is 0–30 in design docs. Unclear if clamping is applied.

### Corrective (max ~15 pts)

| Check | Points |
|-------|--------|
| Wave B retracement within expected range | +5 |
| Wave C structure (impulse-like sub-waves) | +5 |
| Indicator divergence at Wave C end | +5 |

### Triangle (max ~25 pts)

| Check | Points |
|-------|--------|
| Bollinger squeeze inside triangle | +15 |
| MACD near zero in ≥50% of triangle swings | +10 |

---

## Cross-TF Validation (max 20 pts)

Applied in `WaveCounter.crossTfValidate()` after all wave counts are built.

### Non-last timeframe (has a lower TF)

| Check | Points |
|-------|--------|
| W3 internal structure: child TF shows ≥5 swings (odd = impulse-like) | +10 |
| W3 internal structure: child TF shows ≥4 swings (partial confirmation) | +5 |
| W3 internal structure: child TF shows exactly 3 swings | Adds contradicting evidence |

### Last timeframe (no lower TF available)

| Check | Points |
|-------|--------|
| Parent TF has a wave count in the **same direction** | +10 |
| Parent TF dominant direction is opposite AND outscores same-direction by >10 AND score >30 | Adds contradicting evidence |

---

## Alternation Score (max 10 pts)

Tracked as `alternationScore` on `WaveCount`. Checks that W2 and W4 alternate in character (one sharp/deep, one shallow/sideways). Exact scoring logic: `alternationScore` is set during `tryFitImpulseUp` based on depth difference between W2 and W4 retracements.

**⚠ Review note:** The alternation check is present in the score field but the detailed scoring implementation in `WaveCounter` should be verified — ensure it distinguishes sharp (zigzag W2) vs flat (triangle/flat W4) character, not just depth.

---

## Segment Proportionality Bonus (max 15 pts, post-processing)

Applied in `ElliottWaveAnalyzer.applySegmentProportionalityBonus()` using `MarketStructureData.TrendSegment` data.

| Check | Points |
|-------|--------|
| W3 body falls in the segment with the largest absolute price change | +6 |
| W2 and W4 have >20% relative difference in correction depth (alternation via segments) | +5 |
| W5 segment price change < W3 segment price change (W5 divergence) | +4 |

Capped at +15 total.

---

## Bottom-Up Cross-TF Boosts (ElliottWaveAnalyzer)

Applied to parent TF context `crossTfScore` when child TF structure confirms expected wave:

| Condition | Boost |
|-----------|-------|
| Parent at W4 + child shows ZIGZAG, FLAT, or TRIANGLE corrective | +15 pts on parent driving count |
| Parent at WA + child shows 5-wave impulse at W5 stage | +12 pts on parent context |

---

## Structural-Only Mode

When Fibonacci ratios don't fit (all 5 impulse pivots present but ratios outside tolerance), `WaveCounter` attempts a structural-only fit:
- Hard geometric rules still applied (same as above)
- `fibonacciScore = 0`
- `indicatorScore` computed normally
- `structuralOnly = true` flag set on WaveCount
- Exists to capture real-world patterns that don't fit textbook Fibonacci but are structurally valid

---

## Corrective Targets (ScenarioBuilder)

| Wave Type | Stage | Target computation |
|-----------|-------|--------------------|
| ZIGZAG | WC in progress | 100%, 161.8%, 61.8% of WA from WB end |
| ZIGZAG | WB in progress | 61.8%, 50%, 38.2% retrace of WA (WB targets) |
| FLAT / EXPANDED_FLAT / RUNNING_FLAT | WC in progress | 100% of WA from WB end; WA end level |
| FLAT | WB in progress | 61.8%, 50%, 38.2% retrace of WA |
| DOUBLE_ZIGZAG / COMPLEX_WXY | WC (=WY) in progress | 100%, 161.8%, 61.8% of WX from WY start |
| IMPULSE (completed bullish) | UNKNOWN | W4 low, 61.8% retrace, 50% retrace of full impulse |
| IMPULSE (completed bearish) | UNKNOWN | 100%, 61.8%, 161.8% extension of bearish impulse |
| IMPULSE | W5 in progress | 61.8%, 100%, 161.8% of W1 from W4 end |
| IMPULSE | W4 in progress | 38.2%, 50%, 61.8% retrace of W3 |
| TRIANGLE | Any | +10% from last pivot (rough estimate) |

---

## Virgin Trendline Detection

`VirginTrendlineDetector.detect()` processes IMPULSE waves only:

1. Candidate pivots: Origin (W0), W2, W4
2. All pairs (p1, p2) where p1.barIndex < p2.barIndex generate a trendline
3. Virginity check (forward): no ZigZag pivot after p2 breaks the line (support: LOW < line − 0.5ATR; resistance: HIGH > line + 0.5ATR)
4. Backward candle scan (new): from p1 backward, checks raw candle HIGH (resistance) or LOW (support) for touches within 0.5 ATR; validates each touch is not preceded by a close-through break; increments `touchCount` per confirmed prior touch
5. Score = `touchCount − 2` (extra points per prior touch beyond the two anchors)
6. Filtered to within 15% of current price; top 5 support + 5 resistance returned

---

## Known Gaps / Review Questions

1. **indicatorScore cap**: Design says 0–30 but raw impulse indicator scoring can exceed 57 pts. Is there a clamp before storing?

2. **Alternation character**: The `alternationScore` checks depth difference but not wave structure character (zigzag vs flat). Classic Elliott requires W2 and W4 to alternate in *form* (if W2 is sharp/deep, W4 should be sideways/shallow and vice versa). Is structure-type alternation checked?

3. **W4 overlap handling**: The W4/W1 overlap is recorded as a violation but the count is still kept and scored. Should counts with this hard-rule violation be filtered out or scored to zero fibonacciScore?

4. **Triangle target**: Currently `last.getPrice() * 1.10` (flat 10% estimate). Should use pole height projected from breakout point.

5. **W3 shortest rule**: Similar to W4 overlap — added to violations but count kept. Should a W3-shortest count reach the scenario layer at all?

6. **Corrective crossTfScore**: Currently 0 for all corrective/triangle wave counts. Child TF internal structure of Wave A or Wave C (should show 5 sub-waves for impulse character) is not validated.

7. **Running flat vs expanded flat**: Both map to EXPANDED_FLAT/RUNNING_FLAT but the distinction (running flat: C stays above A start) is not enforced — both are accepted as long as B ≥ 90% and C goes beyond A.

8. **Diagonal / ending diagonal**: No separate detection. Diagonals would naturally violate the W4/W1 overlap rule but are real Elliott patterns. Currently handled by keeping the violation-flagged count, but no diagonal-specific scoring or target logic exists.

9. **Wave B > 100% discrimination**: The code checks `bRetrace >= 1.0` to classify as EXPANDED_FLAT but this uses the same branch as regular FLAT (bRetrace ≥ 0.900). Expanded flat (B > 100% of A, C > 100% of A) has different targets than regular flat — targets might need a separate case.

10. **Structural-only counts mix with Fibonacci counts**: Both flow into ScenarioBuilder. High indicator scores on structural-only counts can make them competitive with well-fitting Fibonacci counts. Consider a separate scoring tier or lower ceiling for structural-only.
