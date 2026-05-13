# Spec: VCP Pattern Detection — Java Port

**Source reference (read-only):** `BennyThadikaran/stock-pattern/src/utils.py` lines 282-371 (`is_bullish_vcp`, `is_bearish_vcp`) + 590-789 (`find_bullish_vcp`, `find_bearish_vcp`).
**Target package:** `com.dtech.ta.patterns`
**License note:** Algorithmic logic is not copyrightable. We re-implement the algorithm from the geometric description; no Python source is copied or paraphrased verbatim.

---

## Why this pattern matters
**Volatility Contraction Pattern (Minervini)** — a stage-2 base built from successively tighter pullbacks. Bullish VCP precedes upside breakouts; bearish VCP precedes breakdowns. Our existing pattern scanner has DTB, HNS, Triangle, Flag — but not VCP. This adds the most-cited Minervini base type to our detector arsenal.

## Pattern geometry (5-point)

### Bullish VCP
```
  A         C
   \       /\        E
    \     /  \      /
     \   /    \    /
      \ /      \  /
       B        \/
                D
```
- **A** = significant pivot HIGH (anchor — usually highest in lookback)
- **B** = first pivot LOW after A (deeper pullback)
- **C** = pivot HIGH between B and D, at similar level to A
- **D** = second pivot LOW after C, **higher than B** (contraction)
- **E** = current bar close

**Validity conditions:**
1. `|A - C| <= avgBarLength` — A and C form roughly horizontal resistance
2. `|B - D| >= avgBarLength * 0.8` — the two lows differ visibly (D is the higher low; contraction is meaningful)
3. `B < min(A, C, D, E)` — B is the absolute low
4. `D < min(A, C, E)` — D is the second-lowest
5. `E < C` — current price has not yet broken out above resistance (still forming)
6. `NOT (C > A AND |A - C| >= avgBarLength * 0.5)` — reject when C is clearly higher than A (that's a higher-high breakout, not a VCP base)

### Bearish VCP (mirror)
```
       B
      /\        D
     /  \      /\
    /    \    /  \
   A      \  /    E
           \/
           C
```
- **A** = pivot LOW (anchor)
- **B** = first pivot HIGH after A (rally)
- **C** = pivot LOW between B and D, similar level to A
- **D** = second pivot HIGH after C, lower than B (rally contraction)
- **E** = current close

**Validity conditions:**
1. `|A - C| <= avgBarLength`
2. `|B - D| >= avgBarLength * 0.8`
3. `B > max(A, C, D, E)`
4. `D > max(A, C, E)`
5. `E > C`
6. `NOT (C < A AND |A - C| >= avgBarLength * 0.5)`

## avgBarLength definition
Median of `(High - Low)` across bars in window `[A_index, C_index]` inclusive. This normalizes thresholds to the volatility regime in which the pattern formed.

## Search algorithm (find_bullish_vcp logic)

Given a list of swing pivots over the lookback window:
1. Pick **A** = the pivot with the HIGHEST price.
2. Loop:
   1. Find **B** = LOWEST pivot strictly after A in time.
   2. Find **D** = LOWEST pivot strictly after B in time.
   3. Find **C** = HIGHEST pivot strictly between B and D in time.
   4. Compute `avgBarLength` over `[A_index, C_index]`.
   5. If `isBullishVcp(A, B, C, D, E, avgBarLength)`:
      - Sanity check: between C-index and now, max close <= C; between D-index and now, min close >= D. (Pattern integrity — neither C resistance nor D support has been breached.)
      - If integrity holds: return the pattern.
      - Else: advance A := C, A_index := C_index, continue search (try the next sub-window).
   6. Else: A := C, A_index := C_index, continue.
3. Exit loop when there's no valid B-or-D pivot left (ran off the end).

Mirror logic for bearish: replace "highest" with "lowest" everywhere.

## Java-side class structure

### `VcpPattern` (immutable record)
```
record VcpPattern(
  Direction direction,       // BULLISH | BEARISH
  Pivot a, b, c, d,         // the four anchor pivots
  int endBarIndex,          // E = current bar
  double endPrice,
  double avgBarLength,
  Instant startTime,
  Instant endTime
)
```
Equals/hashCode by (direction, A index, B index, C index, D index).

### `Pivot` (use existing if available)
The codebase already has pivot models — pick one of:
- `com.dtech.ta.elliott.EnrichedPivot`
- `com.dtech.ta.BarTuple` for raw (index, time, price)
- Or whatever the existing pattern detectors use (`DtbHnsCandidateDetector` will tell us).

Use the same pivot model that `DtbHnsCandidateDetector` uses for consistency.

### `VcpDetector`
**Responsibilities:**
- Take a `BarSeries` (ta4j) + list of pivots within a lookback window
- Return `Optional<VcpPattern>` (latest valid pattern) AND `List<VcpPattern>` (historical — for backtest)

**Methods (signatures only — no implementation in this spec):**

```
public class VcpDetector {

  public VcpDetector(BarSeries series, PivotProvider pivots);

  // Find the most recent valid bullish VCP, or empty
  public Optional<VcpPattern> findLatestBullishVcp(int lookbackBars);

  // Find the most recent valid bearish VCP, or empty
  public Optional<VcpPattern> findLatestBearishVcp(int lookbackBars);

  // Find all bullish VCPs whose E is within lookback bars of current
  public List<VcpPattern> findAllBullishVcps(int lookbackBars);

  // Same for bearish
  public List<VcpPattern> findAllBearishVcps(int lookbackBars);

  // Pure geometry checks (package-private, for unit testing)
  static boolean isBullishVcp(double a, double b, double c, double d, double e, double avgBarLength);
  static boolean isBearishVcp(double a, double b, double c, double d, double e, double avgBarLength);
}
```

### `PivotProvider` (interface — adapter to existing pivot system)
```
interface PivotProvider {
  List<Pivot> getPivots(int fromBarIndex, int toBarIndex);
  Pivot highestPivot(int fromBarIndex, int toBarIndex);
  Pivot lowestPivot(int fromBarIndex, int toBarIndex);
  Pivot firstAfter(int barIndex);
}
```
Implement by wrapping whichever pivot engine the project uses (CandidatePivotEngine or ZigZag). The detector should not know about pivot internals.

## Edge cases

1. **Insufficient pivots** — fewer than 4 pivots in lookback → return empty.
2. **All pivots same direction** — no lowest-after-highest sequence possible → return empty.
3. **Pattern integrity breach** — C resistance breached BEFORE E, or D support breached. The algorithm advances A := C and continues, rather than returning a stale pattern.
4. **Multiple overlapping VCPs** — return the most recently completed (highest E index) by default; expose `findAll*` for backtest.
5. **Duplicate timestamps** — if the pivot series has duplicates at the same index, use the most extreme price for that index (max for high pivots, min for low pivots). Python source handles this explicitly; we should too.
6. **avgBarLength is zero** — pathological (no volatility); treat as no pattern.
7. **A and C at exactly the same index** — pivots overlap; not a valid pattern.

## Sanity-check post-validity rules

After geometry passes, verify pattern is still "active" (not already broken or invalidated):
- For bullish: after C is formed, no later close exceeds C; after D is formed, no later close is below D. If either fails, the pattern is post-breakout — advance A and re-search.
- For bearish: mirror.

This matches the "Active vs Confirmed vs Invalid" state model in `MarcosACH/chart-patterns`. We may extend the spec later to expose all three states; for now, return only Active patterns.

## Coding checklist

- [ ] **Inspect existing pivot model** — confirm whether to use `EnrichedPivot`, `BarTuple`, or another. If multiple, pick the one `DtbHnsCandidateDetector` uses.
- [ ] **Add `VcpPattern` record** in `com.dtech.ta.patterns` with direction enum, 4 pivots, end index, avgBarLength, start/end Instant.
- [ ] **Add `Direction` enum** if not present (BULLISH, BEARISH) — may already exist in patterns package.
- [ ] **Add `VcpDetector` class** with:
  - [ ] Constructor `(BarSeries, PivotProvider)`
  - [ ] `findLatestBullishVcp(int lookbackBars)` — returns `Optional<VcpPattern>`
  - [ ] `findLatestBearishVcp(int lookbackBars)` — returns `Optional<VcpPattern>`
  - [ ] `findAllBullishVcps(int lookbackBars)` — returns `List<VcpPattern>`
  - [ ] `findAllBearishVcps(int lookbackBars)` — returns `List<VcpPattern>`
  - [ ] `isBullishVcp(...)` static — pure geometry
  - [ ] `isBearishVcp(...)` static — pure geometry
  - [ ] Private `searchBullish(pivots, fromIdx)` — iterative A := C loop
  - [ ] Private `searchBearish(pivots, fromIdx)` — mirror
  - [ ] Private `avgBarLength(startIdx, endIdx)` — median of (high-low) over slice
- [ ] **Add `PivotProvider` interface** if no equivalent abstraction exists
- [ ] **Add adapter** wrapping existing pivot engine to implement `PivotProvider`
- [ ] **Register detector** in pattern scanner aggregator (mirror how `TriangleDetector`/`FlagPatternDetector` are wired)
- [ ] **Add Javadoc** to public API with the ASCII pattern diagram + reference to this spec
- [ ] **Run `./gradlew compileJava`** — must compile clean
- [ ] **No emoji in source files**

## Testing spec

### Unit tests — pure geometry (`VcpDetectorGeometryTest`)
Each test calls `VcpDetector.isBullishVcp(a, b, c, d, e, avgBarLength)` with fixed scalar inputs. Pure function, no series setup.

| # | Case | a | b | c | d | e | avgBarLength | Expected |
|---|---|---|---|---|---|---|---|---|
| 1 | Textbook bullish VCP | 100 | 90 | 99 | 94 | 97 | 1.0 | true |
| 2 | A and C too far apart (>avgBar) | 100 | 90 | 95 | 94 | 97 | 1.0 | false |
| 3 | B and D too close (no contraction) | 100 | 90 | 99 | 90.5 | 97 | 1.0 | false (\|b-d\| < 0.8*avgBar) |
| 4 | E already broke out above C | 100 | 90 | 99 | 94 | 102 | 1.0 | false |
| 5 | D below B (no contraction) | 100 | 90 | 99 | 88 | 97 | 1.0 | false (d < b, b not min) |
| 6 | C > A by significant margin | 100 | 90 | 105 | 94 | 97 | 1.0 | false |
| 7 | avgBarLength = 0 | 100 | 90 | 100 | 94 | 97 | 0.0 | false (treat as no pattern) |

Mirror set for `isBearishVcp`.

### Integration tests — search logic (`VcpDetectorSearchTest`)
Build a synthetic `BarSeries` with hand-placed pivots and verify the search algorithm finds the expected pattern.

1. **Single clean bullish VCP** — series with 5 pivots forming valid pattern → `findLatestBullishVcp` returns matching pattern; A/B/C/D indices match expected.
2. **Pattern then breakout** — series with a complete VCP, then a few bars that close above C → detector should NOT return that pattern (integrity broken), advances and finds no other pattern.
3. **Two stacked VCPs** — series with two distinct VCPs back to back → `findAllBullishVcps` returns 2 patterns in chronological order; `findLatestBullishVcp` returns the most recent.
4. **No pattern (random walk)** — series with random pivots that violate geometry → returns empty.
5. **Insufficient pivots** — series with only 3 pivots → returns empty.
6. **Symmetric data** — series with a perfect bearish VCP → bearish detector returns it; bullish detector returns empty.

### Acceptance criteria
- All unit tests green
- All integration tests green
- Compile clean: `./gradlew compileJava`
- Existing pattern tests still green: `./gradlew test --tests *Pattern*`
- Detector can be wired into the existing pattern scanner without modifying other detectors

## Out of scope for this port
- Volume confirmation (Minervini's VCP also requires volume contraction with each pullback). Add as `VcpDetectorV2` later if needed.
- Pivot count requirement (some VCP defs require 3+ pullbacks; we model 2). Add `VcpDetectorV2` with N-pullback param later.
- Plotting / visualization. Our charts code is separate.

## Delegation
This spec is sized for one local-worker invocation. The worker reads this doc + existing pivot model + `TriangleDetector` for style reference, then implements all classes + tests in one pass. Spec is self-contained.

---

*Spec author: Claude. Source reference: BennyThadikaran/stock-pattern (GPL-3.0, read-only as reference).*
