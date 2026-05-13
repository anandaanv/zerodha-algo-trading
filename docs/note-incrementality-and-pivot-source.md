# Incrementality of classic-pattern detection + pivot-source choice

**Branch:** feature/chart-pattern-eval
**Date:** 2026-05-11
**Tests:** `CpzzVsWilliamsPivotComparisonTest`, `AllPatternsHourlyScanTest`

## Question 1 — How many classic patterns fire with CPZZ vs Williams?

**Answer:** CPZZ produces ~5× more detections, but overlap with Williams is only 33%. They surface materially different pattern sets.

### Numbers (46 Nifty stocks, hourly bars, Jan 2024+, ~126K total bars)

| | Williams (6/6) | CPZZ (incremental) |
|---|---|---|
| Pivots per stock | ~310 | ~525 (1.7× more) |
| Total pattern detections | 262 | 1,361 (5.2× more) |
| Overlap (±5 bar tolerance) | 86 | 86 |
| Overlap rate (of smaller set) | 33% | 33% |

Per pattern type:

| Pattern | Williams | CPZZ | Williams-only | CPZZ-only |
|---|---|---|---|---|
| TRIANGLE | 81 | 462 | 66 | 447 |
| REV_HNS | 83 | 432 | 57 | 406 |
| HNS | 53 | 278 | 35 | 260 |
| DOUBLE_TOP | 17 | 50 | 6 | 39 |
| DOUBLE_BOTTOM | 16 | 59 | 7 | 50 |
| BULLISH_CRAB | 0 | 57 | 0 | 57 |

### Interpretation

The two pivot sources find essentially different patterns because:
- **Williams** waits N bars of confirmation on each side before declaring a pivot, so it's symmetric-lag detection. Sharp reversals that retrace before N bars are missed.
- **CPZZ** marks candidate pivots via reversal candle patterns and confirms via threshold breach, so it catches faster reversals but can also mark more borderline ones.

Volume alone doesn't mean quality. **We need to measure directional accuracy with each pivot source before recommending a switch.** That's the next step in this evaluation.

## Question 2 — Can detection be incremental?

**Answer: yes, all 23 detectors are inherently incremental-friendly because they're pivot-anchored. The pivot source determines whether the full pipeline can run live.**

### Per-detector class — incrementality assessment

| Detector family | State to maintain | Cost per new bar | Cost per new pivot |
|---|---|---|---|
| **6-point geometric** (Triangle, HNS, ReverseHNS, DT, DB) | Last N pivots + last detected pattern | O(1) — running max | O(N) — check only NEW 6-windows ending at the new pivot |
| **5-point harmonic XABCD** (BAT/Gartley/Crab/Butterfly) | Last N pivots | O(1) | O(N²) for all (X, A) anchors with new pivot as D — same as today |
| **4-point ABCD** | Last N pivots | O(1) | O(N) — fix new pivot as D, search (A, B, C) backwards |
| **VCP** | Last detected base (A, B, C, D) + last close | O(1) | O(N) — check if new pivot extends current base or starts new one |
| **Flag** | SMA20, SMA50, 7d/30d/90d rolling highs | O(1) — all rolling | O(1) — pivot triggers re-eval |
| **Trendline** | List of pivots seen so far | O(1) for bar | O(N) — pair new pivot with all earlier same-type pivots |

**Bottom line:** every detector can be made incremental at O(N) per new pivot, O(1) per new bar (no new pivot). The dominant cost is pivot extraction itself.

### Why this matters

| Mode | Pattern visible | Latency |
|---|---|---|
| Williams (current) | After last pivot + barsRight bars of confirmation | Symmetric-lag: 6 bars on hourly = 6 hours |
| CPZZ (existing engine) | After candle reversal pattern + threshold breach | Asymmetric: typically 0-3 bars |

Switching pivot source from Williams to CPZZ would:
- Reduce detection latency by ~3-6 hourly bars
- Increase detection rate ~5×
- Reduce overlap with current results to ~33%

### Recommended sequence

1. **Add a per-detector accuracy test with CPZZ pivots** — measure directional accuracy of the 1,361 CPZZ-found patterns against the 262 Williams-found patterns. Compare apples-to-apples on the same 46-stock cohort.
2. If CPZZ accuracy is competitive (≥ 55% on at least one pattern with n ≥ 50), introduce a `PivotSource` interface and wire CPZZ-backed implementation alongside Williams. Both feed the same `ClassicPatternDetector<P>` contract.
3. Production wiring (not in this PR):
   - Live scan: CPZZ pivots, run detectors after each `cpzz.processBar()` returns a new confirmed pivot
   - Backtest: either source via config
4. Eventually deprecate `ClassicPivotExtractor` in favor of CPZZ — but only after accuracy data justifies it.

### Open question: pivot source vs detector quality

Our random-formation inspection earlier caught `BULLISH_CRAB` returning a HIGH-typed X. The fix was a pivot-type guard. **CPZZ pivots are also typed (HIGH/LOW)** — the same fix applies. CPZZ alone doesn't fix structural correctness; the detectors must still validate pivot types regardless of source.

---

*Authored by: Claude. Tests cited live in `src/test/java/com/dtech/ta/patterns/classic/`.*
