# Evaluation: BennyThadikaran/stock-pattern

**Date:** 2026-05-09
**Branch:** feature/chart-pattern-eval
**Repo:** https://github.com/BennyThadikaran/stock-pattern
**Successor:** https://github.com/BennyThadikaran/precise-patterns (also GPL-3.0)
**Verdict:** **HIGH-QUALITY REFERENCE; DO NOT LINK; PORT KEY ALGORITHMS TO JAVA.**

---

## Summary stats
- **373 stars**, last commit 2026-05-07 (active)
- Single maintainer (Indian stock trader, examples are NSE stocks — directly relevant)
- License: **GPL-3.0** (viral copyleft)
- Python CLI tool, ~150 KB source
- Author has shipped a successor: `precise-patterns` (Python real-time library, also GPL-3.0)

## What's actually in there
Looking at `src/utils.py` (~104 KB), this is a serious, well-structured pattern-detection codebase:

### Pattern coverage (function names from source)
- **Continuation/reversal:** `find_triangles`, `find_hns`, `find_reverse_hns`, `find_double_top`, `find_double_bottom`, `find_bullish_flag`, `find_bearish_flag`
- **VCP:** `find_bullish_vcp`, `find_bearish_vcp` (Mark Minervini's volatility contraction pattern — we don't have this)
- **Trendlines:** `find_uptrend_line`, `find_downtrend_line`, `generate_trend_line`
- **Harmonic patterns:** ABCD, BAT, Gartley, Crab, Butterfly (each bullish + bearish — 10 total) — we don't have any of these

### Pattern detection methodology (this is the good part)
The library uses **proper pivot-based 6-point geometry** for detection. Example from `is_triangle`:

```python
# Symmetric: descending highs + ascending lows + apex convergence
return a > c > e and b < d < f and e > f
# Ascending: flat resistance + rising lows
return abs(a-c) <= avgBarLength and abs(c-e) <= avgBarLength and b < d < f < e
# Descending: flat support + falling highs
return abs(b-d) <= avgBarLength and a > c > e > f and f >= d
```

This is **structurally correct** — it operates on swing pivots (A, B, C, D, E, F) and checks geometric relationships, not naive rolling-window masks. Same approach our `CandidatePivotEngine` + `TrendlineV2` use.

### `is_hns` — proper geometric check
```python
return (
    c > max(a, e)                        # head higher than both shoulders
    and max(b, d) < min(a, e)            # neckline below shoulder bases
    and f < e                            # breakout candle
    and abs(b - d) < avgBarLength        # neckline level symmetry
    and abs(c - e) > shoulder_height_threshold  # asymmetry from head
)
```
Compare to white07S which just checks "is local pivot" — this is a real H&S definition.

### Volatility-aware
Uses `get_atr()` to compute `avgBarLength` and normalizes pattern thresholds against it. This handles different price regimes correctly (a 5pt move in HUDCO ≠ 5pt in RELIANCE).

## The license problem (GPL-3.0)
GPL-3.0 is viral copyleft. If we link this library into our trading system, the entire trading system becomes subject to GPL — meaning we'd be required to open-source it under GPL if/when we distribute. That's a non-starter for a commercial trading product.

Workarounds (in order of safety):
1. **Don't link. Port the algorithms.** Re-implement `is_triangle`, `is_hns`, `find_bullish_vcp`, harmonic detectors in Java under our existing license. The math/geometry isn't copyrightable; only the specific code is. This is the safe path.
2. **Run as a separate process.** Some interpretations treat HTTP/IPC boundaries as preserving license separation; others don't. FSF would likely disagree. Risky.
3. **Wait for `precise-patterns`.** Author's successor library — also GPL-3.0, so same issue.

## What we'd gain by porting
We currently have: DTB, HNS, Triangle, Flag, ZigZag pivots, TrendlineV2.

We're **missing**:
- **VCP** (Mark Minervini's pattern) — bullish/bearish, useful for breakout setups
- **Harmonic patterns** — BAT, Gartley, Crab, Butterfly (all use Fibonacci ratios; ABCD is the simplest entry point)

These are the two valuable additions. Everything else we already have.

## Specific takeaways from the source

1. **6-point pattern definitions** — clean, testable, geometric. Our DTB detector uses similar structure; our triangle/H&S detectors should follow the exact same A-B-C-D-E-F naming pattern for consistency. Worth standardizing.

2. **`avgBarLength` normalization** — universal. Should apply to all our pattern thresholds (not just ATR-relative SL).

3. **`get_relative_clusters`** — finds price levels close to a reference using mean deviation. Useful for clustering similar pivot heights when validating "shoulder symmetry" or "double-top equal-high" conditions.

4. **Pre-breakout detection emphasis** — README states explicitly: "All patterns are detected, prior to breakout. at the last leg of the pattern." This is what we want — early signal, then confirm with breakout. Our detectors should be similarly early.

## Recommended action

1. **Do not import the library** (GPL contamination).
2. **Read `utils.py`** as a reference for pattern geometry. It's freely licensed for reading/learning under GPL.
3. **Port `find_bullish_vcp` and `find_bearish_vcp`** to Java — these are missing from our codebase and would directly enhance the existing pattern scanner.
4. **Optionally port one or two harmonic patterns** (start with `find_bullish_bat` / `find_bearish_bat` — simplest after ABCD). These add a new pattern class without disturbing existing code.
5. **Check our `is_hns` / `is_triangle` definitions** against the source for any geometric checks we might be missing (e.g., shoulder height threshold in H&S, apex convergence in symmetric triangles).

## Comparison summary — three libraries evaluated

| Library | Stars | License | Quality | Verdict |
|---|---|---|---|---|
| white07S/TradingPatternScanner | 287 | CC BY-NC-SA 4.0 | Poor (rolling-window masks ≠ patterns) | Reject |
| MarcosACH/chart-patterns | 2 | None | Pine Script only | Reject (concept only) |
| BennyThadikaran/stock-pattern | 373 | **GPL-3.0** | **High (proper pivot geometry)** | **Reference + port** |

---

*Evaluated by: Claude (with verification of source via gh API)*
