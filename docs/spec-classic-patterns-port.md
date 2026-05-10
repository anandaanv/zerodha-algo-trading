# Spec: Classic Chart Patterns — Full Java Port

**Source reference (read-only, GPL-3.0):** `BennyThadikaran/stock-pattern/src/utils.py`
**Cached locally for reference:** `/tmp/bt-utils.py`
**Target package:** `com.dtech.ta.patterns.classic` (new, isolated from existing `com.dtech.ta.patterns.*`)
**Target test package:** `com.dtech.ta.patterns.classic` under `src/test/java`

**License rationale:** Algorithmic logic and pattern geometry are not copyrightable. We re-implement from the documented geometric definitions and validity conditions. No source code is copied; specific Python implementation choices (e.g., variable names like `bc_618_ext`) are not retained in Java.

---

## Goals

1. Port ALL chart-pattern detectors from `BennyThadikaran/stock-pattern` into our codebase
2. Keep them isolated in a new package `com.dtech.ta.patterns.classic` — does NOT modify or replace existing pattern detectors
3. Provide a unified scanner entry point so any subset of patterns can be run on a series
4. Each detector has its own unit + integration tests, all in green before merge

## Patterns to port (in implementation order)

### Phase 1 — Infrastructure
- `PivotPoint` (immutable record: barIndex, time, price, type=HIGH/LOW)
- `ClassicPivotExtractor` (Williams-style local max/min over a configurable left/right window — matches `get_max_min` in Python source)
- `ClassicPattern` (sealed interface with concrete records per pattern)
- `Direction` enum (BULLISH, BEARISH)
- `AvgBarLength` utility (median high-low over a [from, to] index range)
- `FibRatios` utility (canonical series 0.236, 0.382, 0.5, 0.618, 0.707, 0.786, 0.886, 1.0)
- `ClassicPatternScanner` (orchestrator; runs selected detectors and returns aggregated `List<ClassicPattern>`)
- `PatternIntegrityChecker` (post-detection: has price already broken the pattern's resistance/support?)

### Phase 2 — 6-point pivot patterns
- **Triangle** (Ascending / Descending / Symmetric)
- **Head and Shoulders** (regular)
- **Reverse Head and Shoulders**
- **Double Top**
- **Double Bottom**

### Phase 3 — VCP
- **Bullish VCP** (Volatility Contraction Pattern)
- **Bearish VCP**

### Phase 4 — Continuation + trendlines
- **Bullish Flag** (high-pole + flag)
- **Bearish Flag**
- **Uptrend line** (trendline support)
- **Downtrend line** (trendline resistance)

### Phase 5 — Harmonic
- **Bullish ABCD** / **Bearish ABCD**
- **Bullish BAT** / **Bearish BAT**
- **Bullish Gartley** / **Bearish Gartley**
- **Bullish Crab** / **Bearish Crab**
- **Bullish Butterfly** / **Bearish Butterfly**

Each pattern has: detector class, immutable record, unit-test class (pure geometry), integration-test class (synthetic series).

---

## Core API design

### Pattern records (sealed hierarchy)
```
sealed interface ClassicPattern permits
  TrianglePattern, HnsPattern, ReverseHnsPattern,
  DoubleTopPattern, DoubleBottomPattern,
  BullishVcpPattern, BearishVcpPattern,
  BullishFlagPattern, BearishFlagPattern,
  TrendlinePattern,
  AbcdPattern, BatPattern, GartleyPattern, CrabPattern, ButterflyPattern {

  Direction direction();
  int startBarIndex();
  int endBarIndex();
  Instant startTime();
  Instant endTime();
  List<PivotPoint> pivots();          // ordered A, B, C, ...
  double avgBarLength();
}
```

### Single-detector contract
```
interface ClassicPatternDetector<P extends ClassicPattern> {
  Optional<P> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars);
  List<P> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars);
}
```

### Scanner aggregator
```
public class ClassicPatternScanner {
  public ClassicPatternScanner(BarSeries series, PivotExtractor pivotExtractor);
  public List<ClassicPattern> scan(int lookbackBars, EnumSet<PatternType> include);
  public List<ClassicPattern> scanAll(int lookbackBars);
}
enum PatternType { TRIANGLE, HNS, REVERSE_HNS, DOUBLE_TOP, DOUBLE_BOTTOM,
                   BULLISH_VCP, BEARISH_VCP, BULLISH_FLAG, BEARISH_FLAG,
                   UPTREND_LINE, DOWNTREND_LINE,
                   BULLISH_ABCD, BEARISH_ABCD, BULLISH_BAT, BEARISH_BAT,
                   BULLISH_GARTLEY, BEARISH_GARTLEY, BULLISH_CRAB, BEARISH_CRAB,
                   BULLISH_BUTTERFLY, BEARISH_BUTTERFLY }
```

### Pivot extractor (Williams-style)
```
public interface PivotExtractor {
  List<PivotPoint> extract(BarSeries series, int barsLeft, int barsRight, PivotType type);
}
enum PivotType { HIGH, LOW, BOTH }
```
Defaults: `barsLeft = barsRight = 6` (matches Python `get_max_min`). Pivot type HIGH uses `series.bar.high`; LOW uses `series.bar.low`.

---

## Pattern-specific geometric rules

### Triangle (`is_triangle`)
6 pivots A-F alternating HIGH/LOW. Avg bar length over [A_idx, F_idx].
| Type | Conditions |
|---|---|
| Ascending | `|A-C| <= avgBar` AND `|C-E| <= avgBar` AND `B < D < F < E` |
| Descending | `|B-D| <= avgBar` AND `A > C > E > F` AND `F >= D` |
| Symmetric | `A > C > E` AND `B < D < F` AND `E > F` |

A, C, E are HIGH pivots; B, D, F are LOW pivots.

### Head & Shoulders (`is_hns`)
6 pivots A(H), B(L), C(H), D(L), E(H), F (current close as proxy for breakout).
- `C > max(A, E)` — head is highest
- `max(B, D) < min(A, E)` — neckline (troughs) is below shoulder bases
- `F < E` — breakout candle is below right shoulder
- `|B - D| < avgBar` — neckline troughs at similar level
- `|C - E| > avgBar * 0.6` — head is meaningfully higher than right shoulder

### Reverse H&S (`is_reverse_hns`)
Mirror with mins/maxes swapped:
- `C < min(A, E)` — inverse head is lowest
- `min(B, D) > max(A, E)` — neckline above shoulder bases
- `F > E`
- `|B - D| < avgBar`
- `|C - E| > avgBar * 0.6`

### Double Top (`is_double_top`)
4 pivots A(H), B(L), C(H), D (current close). Plus volumes aVol, cVol. Plus ATR.
- `C - B < ATR * 4` — neckline-to-second-peak distance bounded
- `|A - C| <= avgBar * 0.5` — twin peaks at same level (tighter than Triangle)
- `cVol < aVol` — second peak on declining volume
- `b < min(a, c)` — neckline (B) is below both peaks
- `b < d < c` — breakout-in-progress (D between B and C)

### Double Bottom (`is_double_bottom`)
Mirror:
- `B - C < ATR * 4`
- `|A - C| <= avgBar * 0.5`
- `cVol < aVol`
- `b > max(a, c)`
- `b > d > c`

### Bullish VCP (`is_bullish_vcp`)
See full spec at `docs/spec-vcp-pattern-port.md`. Summary:
- A and C are highs at similar level; B and D are lows with `D > B` (higher low = contraction); E is current close still below C.
- `|A-C| <= avgBar`, `|B-D| >= avgBar*0.8`, `B = min`, `D < min(A,C,E)`, `E < C`, NOT (C > A by avgBar*0.5).

### Bearish VCP — mirror.

### Bullish Flag (`find_bullish_flag`)
Less pivot-driven, more series-driven:
- New 7-day high `recent_high` formed in last 7 bars, > monthly high and > 90-day high
- SMA20 > SMA50 * 1.08 (strong uptrend regime)
- `recent_low` (after `recent_high_idx`) > `last_pivot + (recent_high - last_pivot) / 2` (Fib 50% retracement holds)
- Flag length >= 5 bars

### Bearish Flag — mirror.

### Trendline (Up / Down)
`generate_trend_line`: given two pivots in time, compute slope m = (p2-p1)/(idx2-idx1) and y-intercept b = p1 - m*idx1. Then `find_uptrend_line` / `find_downtrend_line` walk pivot pairs and validate touchpoints.

### Harmonic patterns (XABCD or ABCD)
All harmonic patterns use Fibonacci retracements between legs.

#### Snap-to-fib utility
```
double snapToFib(double ratio);  // returns the closest value in FIB_SERIES to `ratio`
```
FIB_SERIES = [0.236, 0.382, 0.5, 0.618, 0.707, 0.786, 0.886, 1.0]

#### ABCD (Bullish) — `find_bullish_abcd`
4 pivots: A(H) → B(L) → C(H) → D(L, current).
- B is the lowest low in [A, C]
- C is the highest high from B forward
- A is not the bar's low; B is not the bar's high; C is not the bar's low (well-formed)
- `c_retrace = snap((C-B) / (A-B))` in [0.382, 0.886]
- Compute `ab=cd extension` = C - (A-B); `bc 1.618 ext` = C - (C-B)*1.618; `ab 1.27 ext` = C - (A-B)*1.27; `ab 1.618 ext` = C - (A-B)*1.618
- **Perfect**: `c_retrace == 0.618` AND `ab=cd <= bc_1.618_ext`
- **Alternate**: lowest_close_after_c < ab=cd extension
- **Terminal point**: AB=CD by default, AB=CD if perfect, AB_1.618 if alternate
- Validity: `D < B - (B - terminal) * 0.5` AND CD-completion < 2x AB-completion (in bars) AND closes_below_terminal_after_c < 7 AND if has_tested terminal, `(D_idx - first_test_idx).days < 7`

Bearish ABCD: mirror.

#### BAT (Bullish) — `find_bullish_bat`
5 pivots: X(L) → A(H) → B(L) → C(H) → D(L, current).
- well-formed: highest_high_xb = A, lowest_low_ac = B, highest_high_from_b = C; X not bar's high; A not bar's low; B not bar's high; C not bar's low
- `b_retrace = snap((A-B)/(A-X))` MUST be in {0.382, 0.5}
- `c_retrace = snap((C-B)/(A-B))` MUST be in [0.382, 0.886]
- **Perfect**: b_retrace == 0.5 AND c_retrace in {0.5, 0.618}
- **Alternate**: b_retrace == 0.382
- Terminal point: XA_0.886 retracement (= A - (A-X)*0.886) for regular; XA_1.13 extension (= A - (A-X)*1.13) for alternate
- If NOT alternate AND lowest_close_after_c < X → reject
- Validity: D < B - (B-terminal)*0.5; closes_below_terminal_after_c < 7; if tested, days from first test to D < 7

Bearish BAT: mirror.

#### Gartley, Crab, Butterfly — same XABCD skeleton, different Fib ratios
Will be specced in detail in Phase 5 sub-spec (separate doc) after BAT lands. Initial structural skeleton mirrors BAT.

---

## avgBarLength definition (canonical for all patterns)
```
double avgBarLength(BarSeries series, int fromBarIdx, int toBarIdx);
// returns median of (high - low) over [fromBarIdx, toBarIdx] inclusive
```
Implemented once in `AvgBarLength`. Used by all detectors.

---

## ATR for Double Top/Bottom
```
double atr(BarSeries series, int window, int barIdx);
// 14-bar rolling-mean True Range (matches Python get_atr with window=15 default)
```
Note: Python uses rolling MEAN, not Wilder smoothing. Our existing system uses Wilder. For consistency with the source library's geometry, use rolling MEAN here. Different from our existing ATR — keep it isolated in this package as `ClassicAtr`.

---

## Edge cases (apply to all detectors)

1. **Insufficient pivots** — fewer than required (3-6 depending on pattern) → return empty/Optional.empty().
2. **Pivot at series boundary** — pivot extraction with barsLeft=6, barsRight=6 means first/last 6 bars never produce pivots. Detectors must handle.
3. **Duplicate timestamps** — if two pivots share a barIndex, use max(price) for HIGH and min(price) for LOW pivots.
4. **Pattern integrity** — after pattern formation, verify no later close has breached the structurally important levels (C for VCP, neckline for HNS, etc.). If breached, advance search anchor and continue.
5. **Stale data** — patterns older than lookbackBars are excluded by default.
6. **avgBarLength = 0** — pathological (no volatility); treat as no pattern.

---

## Test conventions

### Unit tests — pure geometry
For each `is_*` predicate (Triangle, HNS, DoubleTop, etc.), a parameterized JUnit5 test:
- 5-10 cases per pattern
- Inputs as scalar doubles (a, b, c, d, e, f, avgBar, atr, vol)
- Expected boolean result
- Edge cases: thresholds at exact boundaries

### Integration tests — full search algorithm
For each `find_*` function, a JUnit5 test:
- Build a synthetic BarSeries with hand-placed pivots that form the pattern
- Run detector
- Assert pattern returned, correct pivot indices/prices, correct direction
- 4-6 cases per pattern: ideal, near-miss, integrity-breached, no pattern, two-overlapping, insufficient data

### Test data helpers
```
class ClassicPatternTestData {
  static BarSeries buildSeries(double[] highs, double[] lows, double[] closes, double[] vols);
  static BarSeries buildBullishVcp();    // pre-canned valid bullish VCP series
  static BarSeries buildBullishAbcd();   // pre-canned valid ABCD series
  // ... one builder per pattern
}
```
Test data builders live alongside tests in test sources.

---

## Acceptance criteria (per phase)

- `./gradlew compileJava` clean (no warnings)
- `./gradlew test --tests com.dtech.ta.patterns.classic.*` all green
- All existing tests still pass: `./gradlew test`
- No modifications to existing pattern detectors in `com.dtech.ta.patterns.*` (the new code is purely additive in `.classic` subpackage)
- No new external dependencies; uses ta4j BarSeries we already have

---

## Delegation plan (local-worker invocations)

| Phase | Worker invocation | Inputs | Deliverable |
|---|---|---|---|
| 1 | `port-classic-infrastructure` | This spec, existing TriangleDetector style ref | PivotPoint, ClassicPivotExtractor, ClassicPattern sealed iface, Direction enum, AvgBarLength, FibRatios, ClassicAtr, ClassicPatternScanner skeleton, base test infra |
| 2a | `port-classic-triangle-hns-dt` | Phase 1 done | TriangleClassicDetector, HnsClassicDetector, ReverseHnsClassicDetector, DoubleTopClassicDetector, DoubleBottomClassicDetector, all tests |
| 3 | `port-classic-vcp` | Phase 2 done + spec-vcp-pattern-port.md | BullishVcpDetector, BearishVcpDetector, tests |
| 4 | `port-classic-flag-trendline` | Phase 3 done | BullishFlagClassicDetector, BearishFlagClassicDetector, UptrendLineDetector, DowntrendLineDetector, tests |
| 5a | `port-classic-abcd-bat` | Phase 4 done | AbcdBullishDetector, AbcdBearishDetector, BatBullishDetector, BatBearishDetector, harmonic-pattern test infra, tests |
| 5b | `port-classic-gartley-crab-butterfly` | Phase 5a done + sub-spec | Remaining 6 harmonic detectors, tests |

Each invocation should be sized for one local-worker run (1-3 hours of work, 5-15 files, no cross-phase dependencies inside the invocation).

---

## Open questions to confirm before phase 1

1. **Pivot model:** use a new `PivotPoint` record in `com.dtech.ta.patterns.classic`, or adapt the existing `com.dtech.ta.elliott.EnrichedPivot`? Recommendation: **new record**, keeps the package self-contained.
2. **BarSeries type:** use ta4j `BarSeries` directly. Confirmed by existing `TriangleDetector`.
3. **Direction enum:** check if `com.dtech.ta.patterns` already has one. If yes, reuse; if no, create in `classic` subpackage.
4. **Volume access:** ta4j `Bar.getVolume()` returns Num. Cast via `.doubleValue()`. Existing detectors do this.
5. **Lookback default:** 90 bars (matches Python `find_bullish_flag` 90-day max). Configurable per call.

---

*Spec author: Claude. Source reference: BennyThadikaran/stock-pattern (GPL-3.0, read-only as algorithmic reference).*
