# Advanced Elliott Wave Engine — Phased Build Plan
## For local code model (qwen2.5-coder:7b, small context window)

**Date:** 2026-03-24
**Branch:** `advanded-elliott`
**Spec source:** `docs/elliott_engine_lld_spec.md`
**Language:** Java 17, Spring Boot, ta4j

---

## Reading Instructions for Code Model

Each phase below is **self-contained**. For each phase:
- Read ONLY the files listed under "Files to read"
- Build ONLY the classes listed under "Build"
- Write ONLY the tests listed under "Tests"
- Do NOT change any file not listed
- Paste exact class signatures as written — do not rename
- All new classes go in the package specified

---

## Gap Analysis: What Already Exists

| LLD Component | Status | Location |
|---|---|---|
| ZigZag pivot detection | EXISTS | `com.dtech.chartpattern.zigzag.ZigZagPoint` |
| Pivot enrichment (Layer 4) | EXISTS | `com.dtech.ta.elliott.PivotIndicatorEnricher` |
| Pattern detection (Layer 5) | EXISTS | `com.dtech.ta.elliott.PivotPatternDetector` |
| Wave counting (Layer 6) | EXISTS | `com.dtech.ta.elliott.WaveCounter` |
| Scenario building (Layer 7) | EXISTS | `com.dtech.ta.elliott.ScenarioBuilder` |
| Cross-TF analysis (Layer 8) | EXISTS | `com.dtech.ta.elliott.ElliottWaveAnalyzer` |
| Analysis output model | EXISTS | `com.dtech.ta.elliott.ElliottWaveAnalysis` |
| Market structure labels | EXISTS | `com.dtech.kitecon.service.copilot.dto.MarketStructureData` |
| **Swing model + SwingBuilder** | **MISSING** | — |
| **Multi-degree pivot sets** | **MISSING** | — |
| **Confluence zones** | **MISSING** | — |
| **Fib level builder** | **MISSING** | — |
| **Horizontal S/R builder** | **MISSING** | — |
| **Decision zone classifier** | **MISSING** | — |
| **Scenario status enum** | **MISSING** | — |
| **Candle trigger detection** | **MISSING** | — |
| **Entry candidate model** | **MISSING** | — |
| **Trigger entry builder** | **MISSING** | — |
| **Hypothesis state manager** | **MISSING** | — |
| Controller wiring | **MISSING** | — |

---

## Phase 1 — Swing Model + SwingBuilder

### Purpose
Create the `Swing` domain object and the `SwingBuilder` service that converts consecutive `ZigZagPoint` pivots into swings with metrics. Swings are required by the confluence engine and scoring upgrades in later phases.

### Files to read before coding
- `src/main/java/com/dtech/chartpattern/zigzag/ZigZagPoint.java`
- `src/main/java/com/dtech/ta/elliott/EnrichedPivot.java` (to understand pivot structure)

### Package
`com.dtech.ta.elliott.swing`

### Build: Swing.java

```java
package com.dtech.ta.elliott.swing;

public class Swing {
    private String id;               // e.g. "INFY_1D_12_17"
    private String symbol;
    private String timeframe;
    private int startPivotIndex;     // barIndex of start ZigZagPoint
    private int endPivotIndex;       // barIndex of end ZigZagPoint
    private long startTimestamp;
    private long endTimestamp;
    private double startPrice;
    private double endPrice;
    private String direction;        // "UP" or "DOWN"
    private double priceChange;      // endPrice - startPrice (signed)
    private double percentChange;    // abs((endPrice-startPrice)/startPrice)*100
    private int barCount;            // endPivotIndex - startPivotIndex
    private double slope;            // priceChange / barCount
    // constructors, getters, builder pattern
}
```

### Build: SwingBuilder.java

```java
package com.dtech.ta.elliott.swing;

@Service
public class SwingBuilder {

    /**
     * Build swings from an ordered list of ZigZagPoints.
     * A swing is the move from pivot[i] to pivot[i+1].
     * @param pivots   ordered list of ZigZagPoints (alternating HIGH/LOW)
     * @param symbol   instrument symbol
     * @param timeframe  e.g. "1D"
     * @return list of Swing objects in chronological order
     */
    public List<Swing> build(List<ZigZagPoint> pivots, String symbol, String timeframe)
}
```

**Logic for build():**
- For each consecutive pair (pivots[i], pivots[i+1]):
  - `direction` = if endPrice > startPrice then "UP" else "DOWN"
  - `priceChange` = endPrice - startPrice
  - `percentChange` = abs(priceChange / startPrice) * 100
  - `barCount` = endPivotIndex - startPivotIndex
  - `slope` = priceChange / max(barCount, 1)
  - `id` = symbol + "_" + timeframe + "_" + startPivotIndex + "_" + endPivotIndex
- Return as list in order

### Tests: SwingBuilderTest.java

```
package: com.dtech.ta.elliott.swing
class: SwingBuilderTest

Test 1 — basic_up_swing
  Input: two ZigZagPoints: LOW at index=2, price=100; HIGH at index=8, price=120
  Expected: one swing, direction=UP, priceChange=20, percentChange=20.0, barCount=6

Test 2 — basic_down_swing
  Input: two ZigZagPoints: HIGH at index=2, price=150; LOW at index=7, price=120
  Expected: one swing, direction=DOWN, priceChange=-30, percentChange=20.0, barCount=5

Test 3 — multiple_swings
  Input: 4 pivots alternating LOW/HIGH/LOW/HIGH at indices 1,4,7,12 prices 100,130,110,145
  Expected: 3 swings: UP(100→130), DOWN(130→110), UP(110→145)
  Check: all swing IDs are unique

Test 4 — empty_input
  Input: empty list
  Expected: empty list returned, no exception

Test 5 — single_pivot
  Input: one pivot
  Expected: empty list (can't form a swing with one pivot)

Test 6 — slope_correctness
  Input: LOW at index=0 price=100; HIGH at index=5 price=125
  Expected: slope = 25/5 = 5.0

Test 7 — id_format
  Input: symbol="INFY", timeframe="1D", pivots at barIndex=3 and barIndex=9
  Expected: id = "INFY_1D_3_9"
```

---

## Phase 2 — Multi-Degree Pivot Builder

### Purpose
Build `MultiDegreePivotSet` (value object) and `MultiDegreePivotBuilder` service that runs ZigZag at three sensitivity levels (fine/medium/coarse) by varying the ATR multiplier. This enables degree-aware analysis.

### Files to read before coding
- `src/main/java/com/dtech/chartpattern/zigzag/ZigZagPoint.java`
- `src/main/java/com/dtech/chartpattern/zigzag/ZigZagParams.java`
- Look for any existing ZigZag calculator class in `com.dtech.chartpattern.zigzag` package

### Package
`com.dtech.ta.elliott.decomposition`

### Build: PivotDegree.java (enum)

```java
package com.dtech.ta.elliott.decomposition;

public enum PivotDegree {
    FINE,    // most pivots, smallest moves
    MEDIUM,  // mid-level structural pivots
    COARSE   // major pivots only
}
```

### Build: MultiDegreePivotSet.java

```java
package com.dtech.ta.elliott.decomposition;

public class MultiDegreePivotSet {
    private String symbol;
    private String timeframe;
    private List<ZigZagPoint> finePivots;    // ATR mult = 0.5 (default or lower)
    private List<ZigZagPoint> mediumPivots;  // ATR mult = 1.0
    private List<ZigZagPoint> coarsePivots;  // ATR mult = 2.0

    public List<ZigZagPoint> getByDegree(PivotDegree degree) {
        // returns the appropriate list
    }
    // getters, constructor
}
```

### Build: MultiDegreePivotBuilder.java

```java
package com.dtech.ta.elliott.decomposition;

@Service
public class MultiDegreePivotBuilder {

    // ATR multipliers per degree
    private static final double FINE_ATR_MULT   = 0.5;
    private static final double MEDIUM_ATR_MULT = 1.0;
    private static final double COARSE_ATR_MULT = 2.0;

    /**
     * Run ZigZag at 3 ATR multipliers and return all three pivot sets.
     * @param barSeries  ta4j BarSeries for the symbol+timeframe
     * @param symbol     instrument symbol
     * @param timeframe  timeframe string e.g. "1D"
     * @return MultiDegreePivotSet with fine/medium/coarse pivots
     */
    public MultiDegreePivotSet build(BarSeries barSeries, String symbol, String timeframe)
}
```

**Logic for build():**
- Create `ZigZagParams` three times, only changing `atrMult` to 0.5, 1.0, 2.0 (use `ZigZagParams.ofDefaults()` as base, then copy with new atrMult)
- Find or inject the existing ZigZag calculator service (look for a `ZigZagService` or similar in `com.dtech.chartpattern.zigzag`)
- Call it for each params set
- Wrap results in `MultiDegreePivotSet`

### Tests: MultiDegreePivotBuilderTest.java

```
package: com.dtech.ta.elliott.decomposition
class: MultiDegreePivotBuilderTest

Test 1 — coarse_has_fewer_pivots_than_fine
  Input: synthetic BarSeries of 100 bars with oscillating prices
  Expected: coarsePivots.size() <= mediumPivots.size() <= finePivots.size()

Test 2 — all_three_degrees_non_empty
  Input: 50 bars with clear swings
  Expected: all three lists non-empty

Test 3 — getByDegree_returns_correct_list
  Call getByDegree(FINE) → returns finePivots
  Call getByDegree(MEDIUM) → returns mediumPivots
  Call getByDegree(COARSE) → returns coarsePivots

Test 4 — pivots_alternate_high_low
  For each degree, verify the pivot sequence strictly alternates HIGH/LOW
```

---

## Phase 3 — Fib Level Builder + Horizontal S/R Builder

### Purpose
Build two standalone utility services for computing price zones: `FibLevelBuilder` (retracements/extensions from a swing) and `HorizontalSRBuilder` (static S/R from pivot clusters). These feed the confluence engine in Phase 4.

### Files to read before coding
- `src/main/java/com/dtech/ta/elliott/swing/Swing.java` (built in Phase 1)
- `src/main/java/com/dtech/chartpattern/zigzag/ZigZagPoint.java`

### Package
`com.dtech.ta.elliott.confluence`

### Build: PriceLevel.java

```java
package com.dtech.ta.elliott.confluence;

public class PriceLevel {
    private double price;
    private String label;        // e.g. "W2_61.8_retracement", "W3_161.8_extension", "SR_cluster"
    private String sourceType;   // "FIB_RETRACEMENT", "FIB_EXTENSION", "HORIZONTAL_SR"
    private double tolerance;    // ± price tolerance for zone matching
    // constructors, getters
}
```

### Build: FibLevelBuilder.java

```java
package com.dtech.ta.elliott.confluence;

@Service
public class FibLevelBuilder {

    // Retracement ratios
    private static final double[] RETRACE_RATIOS = {0.236, 0.382, 0.500, 0.618, 0.786};
    // Extension ratios
    private static final double[] EXTENSION_RATIOS = {1.000, 1.272, 1.618, 2.000, 2.618};

    /**
     * Compute retracement levels from a completed swing.
     * Retracements are measured from swing.endPrice back toward swing.startPrice.
     * @param swing  the completed swing to retrace from
     * @param tolerancePct  zone tolerance e.g. 0.005 = 0.5%
     * @return list of PriceLevel (one per ratio)
     */
    public List<PriceLevel> buildRetracementLevels(Swing swing, double tolerancePct)

    /**
     * Compute extension targets from a base swing projected forward.
     * @param baseSwing  the impulse or A-leg swing
     * @param originPrice  price where extension is projected from (e.g. end of W2 or B-leg)
     * @param tolerancePct  zone tolerance
     * @return list of PriceLevel (one per ratio)
     */
    public List<PriceLevel> buildExtensionLevels(Swing baseSwing, double originPrice, double tolerancePct)
}
```

**Retracement logic:**
- For an UP swing: retrace levels are at `endPrice - ratio * abs(priceChange)` for each ratio
- For a DOWN swing: retrace levels are at `endPrice + ratio * abs(priceChange)` for each ratio
- Label format: `"FIBO_" + (ratio*100) + "_RET"`
- tolerance = abs(priceChange) * tolerancePct

**Extension logic:**
- For an UP baseSwing: extension levels are at `originPrice + ratio * abs(baseSwing.priceChange)` for each ratio
- For a DOWN baseSwing: extension levels at `originPrice - ratio * abs(baseSwing.priceChange)`
- Label format: `"FIBO_" + (ratio*100) + "_EXT"`

### Build: HorizontalSRBuilder.java

```java
package com.dtech.ta.elliott.confluence;

@Service
public class HorizontalSRBuilder {

    /**
     * Build horizontal S/R zones from a list of pivots.
     * Cluster pivots that are within tolerancePct of each other into a zone.
     * Zones touched by more pivots score higher.
     * @param pivots        list of ZigZagPoint (medium or coarse degree)
     * @param tolerancePct  e.g. 0.01 = 1% clustering tolerance
     * @return list of PriceLevel, each representing a cluster centroid
     */
    public List<PriceLevel> buildSRLevels(List<ZigZagPoint> pivots, double tolerancePct)
}
```

**Clustering logic:**
- Sort pivots by price
- For each pivot, check if its price falls within `centroid ± centroid*tolerancePct` of an existing cluster
- If yes, add to cluster and update centroid as average
- If no, create new cluster
- After clustering, create one `PriceLevel` per cluster
- Label: `"SR_cluster_N"` where N = number of pivots in cluster
- tolerance = centroid * tolerancePct

### Tests: FibLevelBuilderTest.java

```
Test 1 — retracement_up_swing
  Input: UP swing from 100 to 150 (priceChange=50), tolerance=0.005
  Expected: 5 levels at prices: 150-(0.236*50)=138.2, 150-(0.382*50)=130.9,
            150-(0.5*50)=125, 150-(0.618*50)=119.1, 150-(0.786*50)=110.7
  Labels: FIBO_23.6_RET, FIBO_38.2_RET, FIBO_50.0_RET, FIBO_61.8_RET, FIBO_78.6_RET

Test 2 — retracement_down_swing
  Input: DOWN swing from 150 to 100 (priceChange=-50), tolerance=0.005
  Expected: levels at: 100+(0.236*50)=111.8, 100+(0.382*50)=119.1, ...

Test 3 — extension_up_base
  Input: UP swing 100→150, originPrice=130 (e.g. W2 low), tolerance=0.005
  Expected: 5 extension levels starting at 130+(1.0*50)=180, 130+(1.272*50)=193.6, etc.

Test 4 — correct_count
  Always returns exactly 5 retracement levels and 5 extension levels
```

### Tests: HorizontalSRBuilderTest.java

```
Test 1 — cluster_nearby_pivots
  Input: 4 pivots at prices 100.0, 100.5, 99.8, 101.0 (tolerancePct=0.02)
  Expected: 1 cluster (all within 2% of each other), label contains "4"

Test 2 — separate_clusters
  Input: pivots at 100, 100.5, 200, 200.3 (tolerancePct=0.01)
  Expected: 2 clusters

Test 3 — single_pivot
  Input: one pivot at price 150
  Expected: 1 level at 150

Test 4 — empty_input
  Expected: empty list
```

---

## Phase 4 — Confluence Aggregator + Decision Zone Classifier

### Purpose
The core of the confluence engine. Takes all price levels from Fib builder and S/R builder, merges overlapping ones into `ConfluenceZone` objects, scores them, and classifies them as decision zones.

### Files to read before coding
- `src/main/java/com/dtech/ta/elliott/confluence/PriceLevel.java` (Phase 3)
- `src/main/java/com/dtech/ta/elliott/WaveScenario.java`
- `src/main/java/com/dtech/ta/elliott/WaveHypothesis.java`

### Package
`com.dtech.ta.elliott.confluence`

### Build: ConfluenceZone.java

```java
package com.dtech.ta.elliott.confluence;

public class ConfluenceZone {
    private String id;               // e.g. "CZ_1"
    private double lowerPrice;
    private double upperPrice;
    private double midPrice;         // (lower+upper)/2
    private List<PriceLevel> contributingLevels;
    private int factorCount;         // contributingLevels.size()
    private int factorDiversity;     // count of distinct sourceTypes in contributors
    private double score;            // factorCount * factorDiversity (simple initial formula)
    private String zoneType;         // "FIB_CLUSTER", "SR_ONLY", "MIXED", "DECISION"
    private List<String> explanation; // human-readable reasons
    // constructors, getters
}
```

### Build: ConfluenceAggregator.java

```java
package com.dtech.ta.elliott.confluence;

@Service
public class ConfluenceAggregator {

    /**
     * Merge all price levels from multiple sources into confluence zones.
     * Two levels overlap if their tolerance bands intersect.
     * @param allLevels   combined list from FibLevelBuilder + HorizontalSRBuilder
     * @return list of ConfluenceZone, sorted by score descending
     */
    public List<ConfluenceZone> aggregate(List<PriceLevel> allLevels)
}
```

**Merge logic:**
- Sort all levels by price
- For each level, check if it overlaps any existing zone: `level.price - level.tolerance <= zone.upperPrice && level.price + level.tolerance >= zone.lowerPrice`
- If overlaps: expand zone bounds to include this level, add level to contributingLevels
- If no overlap: create new zone with lowerPrice = `level.price - level.tolerance`, upperPrice = `level.price + level.tolerance`
- After merging, compute `midPrice`, `factorCount`, `factorDiversity`, `score`
- `zoneType`: if only FIB sources → "FIB_CLUSTER"; if only SR → "SR_ONLY"; mixed → "MIXED"
- Sort by score descending

### Build: DecisionZoneClassifier.java

```java
package com.dtech.ta.elliott.confluence;

@Service
public class DecisionZoneClassifier {

    /**
     * Classify high-score zones as decision zones if they align with active scenarios.
     * Promotes zone zoneType to "DECISION" and adds explanation.
     * @param zones      output from ConfluenceAggregator
     * @param scenarios  active WaveScenario list from ScenarioBuilder
     * @param minScore   minimum score to consider for DECISION classification (e.g. 4.0)
     * @return same zones list, with qualifying zones updated in place
     */
    public List<ConfluenceZone> classify(List<ConfluenceZone> zones,
                                          List<WaveScenario> scenarios,
                                          double minScore)
}
```

**Classification logic:**
- For each zone with `score >= minScore`:
  - Check if any `WaveHypothesis.invalidationLevel` is within 3% of `zone.midPrice` → add "near_invalidation" to explanation
  - Check if any `WaveHypothesis.targetZones` (FibTarget) level is within 2% of `zone.midPrice` → add "near_target"
  - If explanation is non-empty, set `zoneType = "DECISION"`
- Return updated list

### Tests: ConfluenceAggregatorTest.java

```
Test 1 — non_overlapping_levels
  Input: 3 levels at prices 100, 150, 200, each with tolerance=1.0
  Expected: 3 zones, factorCount=1 each

Test 2 — overlapping_fib_and_sr
  Input: PriceLevel(price=100, tolerance=2, source=FIB_RETRACEMENT),
         PriceLevel(price=101, tolerance=2, source=HORIZONTAL_SR)
  Expected: 1 zone, factorCount=2, factorDiversity=2, zoneType=MIXED

Test 3 — sorted_by_score_descending
  Create 3 zones with scores 6, 2, 9
  Expected output order: 9, 6, 2

Test 4 — empty_input
  Expected: empty list
```

### Tests: DecisionZoneClassifierTest.java

```
Test 1 — zone_promoted_when_near_invalidation
  Create a zone at midPrice=100, score=6
  Create a WaveHypothesis with invalidationLevel=101 (within 3%)
  Expected: zone.zoneType = "DECISION", explanation contains "near_invalidation"

Test 2 — zone_not_promoted_when_score_too_low
  Zone midPrice=100, score=2.0, minScore=4.0
  Expected: zoneType unchanged even if near invalidation

Test 3 — zone_not_promoted_when_no_scenario_alignment
  Zone midPrice=100, score=8.0
  No hypothesis within 3% of 100
  Expected: zoneType not set to DECISION
```

---

## Phase 5 — Scenario Status Enum + ScenarioStatus Tracker

### Purpose
Add formal `ScenarioStatus` enum and a lightweight `ScenarioStatusTracker` that evaluates current `WaveScenario` objects and assigns statuses. This prepares for the Hypothesis Manager in Phase 7.

### Files to read before coding
- `src/main/java/com/dtech/ta/elliott/WaveScenario.java`
- `src/main/java/com/dtech/ta/elliott/WaveHypothesis.java`
- `src/main/java/com/dtech/ta/elliott/WaveCount.java`

### Package
`com.dtech.ta.elliott.scenario`

### Build: ScenarioStatus.java (enum)

```java
package com.dtech.ta.elliott.scenario;

public enum ScenarioStatus {
    LEADING,           // highest score, no hard invalidation, in expected zone
    ACTIVE_ALTERNATE,  // valid, not leading, score above floor
    WEAK_ALTERNATE,    // valid but low score or unresolved
    AWAITING_TRIGGER,  // scenario valid but no trigger in zone yet
    INVALIDATED,       // hard invalidation rule broken
    COMPLETED          // scenario reached its target
}
```

### Build: ScoredScenario.java

```java
package com.dtech.ta.elliott.scenario;

public class ScoredScenario {
    private WaveScenario scenario;
    private ScenarioStatus status;
    private double totalScore;       // sum of best hypothesis scores
    private List<String> statusReasons;  // why this status was assigned
    // constructors, getters
}
```

### Build: ScenarioStatusTracker.java

```java
package com.dtech.ta.elliott.scenario;

@Service
public class ScenarioStatusTracker {

    private static final double ACTIVE_SCORE_FLOOR = 15.0;
    private static final double WEAK_SCORE_FLOOR = 5.0;

    /**
     * Evaluate each scenario and assign a status.
     * @param scenarios    from ScenarioBuilder
     * @param currentPrice current last close price
     * @return list of ScoredScenario sorted by totalScore descending
     */
    public List<ScoredScenario> evaluate(List<WaveScenario> scenarios, double currentPrice)
}
```

**Status assignment logic:**
1. Compute `totalScore` for each scenario = sum of `WaveHypothesis.totalScore` across top 2 hypotheses
2. Check hard invalidation: if `currentPrice` has crossed `WaveScenario.scenarioInvalidation` → set `INVALIDATED`
3. If not invalidated:
   - If `totalScore >= ACTIVE_SCORE_FLOOR`:
     - First (highest-score) non-invalidated scenario → `LEADING`
     - Rest above floor → `ACTIVE_ALTERNATE`
   - If `totalScore >= WEAK_SCORE_FLOOR` → `WEAK_ALTERNATE`
   - Else → `AWAITING_TRIGGER`
4. Sort by totalScore descending

### Tests: ScenarioStatusTrackerTest.java

```
Test 1 — highest_score_is_leading
  3 scenarios with scores 30, 20, 10, none invalidated
  Expected: first = LEADING, second = ACTIVE_ALTERNATE, third = AWAITING_TRIGGER

Test 2 — invalidated_when_price_crosses_invalidation
  WaveScenario with scenarioInvalidation=100 (invalidation below that means bullish invalidated)
  For a BULLISH_CONTINUATION scenario, if currentPrice < invalidationLevel → INVALIDATED
  Note: check WaveScenario field for how invalidation is stored

Test 3 — all_weak_below_floor
  3 scenarios all with totalScore < 5.0
  Expected: all AWAITING_TRIGGER

Test 4 — empty_scenarios
  Expected: empty list, no exception
```

---

## Phase 6 — Candle Trigger Detector + Entry Builder

### Purpose
Detect candle-pattern triggers (hammer, engulfing, wick rejection) and build `EntryCandidate` objects for scenarios that have a confluence zone and trigger alignment.

### Files to read before coding
- `src/main/java/com/dtech/ta/elliott/scenario/ScoredScenario.java` (Phase 5)
- `src/main/java/com/dtech/ta/elliott/confluence/ConfluenceZone.java` (Phase 4)
- `src/main/java/com/dtech/ta/elliott/WaveHypothesis.java`
- Search for how `BarSeries` bars are accessed (ta4j `Bar` interface: `getOpenPrice()`, `getHighPrice()`, `getLowPrice()`, `getClosePrice()`)

### Package
`com.dtech.ta.elliott.trigger`

### Build: TriggerType.java (enum)

```java
package com.dtech.ta.elliott.trigger;

public enum TriggerType {
    HAMMER,
    SHOOTING_STAR,
    BULLISH_ENGULFING,
    BEARISH_ENGULFING,
    LONG_LOWER_WICK_REJECTION,
    LONG_UPPER_WICK_REJECTION,
    NONE
}
```

### Build: CandleTrigger.java

```java
package com.dtech.ta.elliott.trigger;

public class CandleTrigger {
    private TriggerType type;
    private int barIndex;
    private double triggerPrice;   // entry-relevant price (close for hammer, etc.)
    private boolean bullish;
    private List<String> reasons;  // e.g. "body < 30% of range", "lower wick > 60% of range"
    // constructors, getters
}
```

### Build: CandleTriggerDetector.java

```java
package com.dtech.ta.elliott.trigger;

@Service
public class CandleTriggerDetector {

    /**
     * Scan the last N bars for candle trigger patterns.
     * @param barSeries   full bar series
     * @param lookback    how many bars back to scan (e.g. 3)
     * @return list of CandleTrigger found (may be empty)
     */
    public List<CandleTrigger> detect(BarSeries barSeries, int lookback)
}
```

**Detection rules (use ta4j Bar API for OHLC):**

For each bar in the lookback window, compute:
- `open = bar.getOpenPrice().doubleValue()`
- `high = bar.getHighPrice().doubleValue()`
- `low = bar.getLowPrice().doubleValue()`
- `close = bar.getClosePrice().doubleValue()`
- `range = high - low`
- `bodySize = abs(close - open)`
- `bodyRatio = bodySize / max(range, 0.0001)`
- `upperWick = high - max(open, close)`
- `lowerWick = min(open, close) - low`
- `closeLocation = (close - low) / max(range, 0.0001)` (0=bottom, 1=top)

**HAMMER:** `bodyRatio < 0.35 && lowerWick >= 0.55 * range && closeLocation > 0.55` → bullish=true
**SHOOTING_STAR:** `bodyRatio < 0.35 && upperWick >= 0.55 * range && closeLocation < 0.45` → bullish=false
**BULLISH_ENGULFING:** Current close > prev open AND current open < prev close AND prev close < prev open → bullish=true
**BEARISH_ENGULFING:** Current close < prev open AND current open > prev close AND prev close > prev open → bullish=false
**LONG_LOWER_WICK_REJECTION:** `lowerWick >= 0.65 * range` but not strict hammer body → bullish=true
**LONG_UPPER_WICK_REJECTION:** `upperWick >= 0.65 * range` but not strict star → bullish=false

### Build: EntryCandidate.java

```java
package com.dtech.ta.elliott.trigger;

public class EntryCandidate {
    private String id;
    private String symbol;
    private String scenarioId;      // WaveScenario.id
    private String hypothesisId;    // WaveHypothesis.id
    private boolean bullish;
    private String entryStyle;      // "AGGRESSIVE", "MODERATE", "CONSERVATIVE"
    private TriggerType triggerType;
    private double entryPrice;
    private double stopLoss;
    private double target1;
    private double target2;
    private double riskRewardRatio; // (target1 - entryPrice) / (entryPrice - stopLoss) for long
    private List<String> rationale;
    // constructors, getters
}
```

### Build: EntryBuilder.java

```java
package com.dtech.ta.elliott.trigger;

@Service
public class EntryBuilder {

    /**
     * Build entry candidates for each scored scenario that has:
     * (a) a LEADING or ACTIVE_ALTERNATE status
     * (b) a DECISION zone near the trigger bar
     * (c) a valid CandleTrigger in the right direction
     *
     * @param scoredScenarios   from ScenarioStatusTracker
     * @param triggers          from CandleTriggerDetector
     * @param decisionZones     from DecisionZoneClassifier
     * @param currentPrice      latest close
     * @return list of EntryCandidate
     */
    public List<EntryCandidate> build(
        List<ScoredScenario> scoredScenarios,
        List<CandleTrigger> triggers,
        List<ConfluenceZone> decisionZones,
        double currentPrice
    )
}
```

**Build logic:**
- For each LEADING or ACTIVE_ALTERNATE scenario:
  - Get the top-scoring WaveHypothesis from the scenario
  - Check if `currentPrice` is inside any `decisionZone` (lowerPrice ≤ currentPrice ≤ upperPrice) or within 1% of it
  - If yes: find any CandleTrigger that matches the hypothesis direction (`bullish == hypothesis.nextMajorMoveUp`)
  - If trigger found:
    - `entryPrice` = trigger.triggerPrice
    - `stopLoss` = hypothesis.invalidationLevel
    - `target1` = hypothesis.primaryTarget.level (if exists)
    - `target2` = hypothesis.targetZones second entry if present
    - `riskRewardRatio` = abs(target1 - entryPrice) / abs(entryPrice - stopLoss)
    - `entryStyle` = if trigger is HAMMER/ENGULFING → "AGGRESSIVE"; else "MODERATE"
    - Build rationale list with: trigger type, zone name, scenario family
  - Append to results

### Tests: CandleTriggerDetectorTest.java

```
Test 1 — detects_hammer
  Build a bar: open=110, high=112, low=100, close=109
  range=12, body=1 (bodyRatio=0.083), lowerWick=9 (75% of range), closeLocation=0.75
  Expected: HAMMER detected, bullish=true

Test 2 — detects_shooting_star
  Bar: open=100, high=112, low=99, close=101
  range=13, body=1, upperWick=11 (84%), closeLocation=0.15
  Expected: SHOOTING_STAR, bullish=false

Test 3 — no_trigger_on_normal_bar
  Bar: open=100, high=105, low=98, close=103 (normal bar)
  Expected: no trigger of type HAMMER or SHOOTING_STAR

Test 4 — bullish_engulfing
  prev bar: open=105, close=100 (down bar)
  curr bar: open=98, close=106 (up bar that engulfs prev)
  Expected: BULLISH_ENGULFING, bullish=true

Test 5 — empty_lookback_zero
  lookback=0
  Expected: empty list
```

### Tests: EntryBuilderTest.java

```
Test 1 — entry_created_when_trigger_in_decision_zone
  Setup: LEADING scenario, bullish hypothesis, invalidationLevel=90, primaryTarget.level=130
  Decision zone: lower=100, upper=102
  Trigger: HAMMER at price=101, bullish=true
  currentPrice=101
  Expected: 1 EntryCandidate, entryStyle=AGGRESSIVE, stopLoss=90, target1=130

Test 2 — no_entry_when_trigger_wrong_direction
  BULLISH scenario, but trigger is SHOOTING_STAR (bullish=false)
  Expected: empty list

Test 3 — no_entry_when_not_in_decision_zone
  currentPrice=200, decision zone is 100-102
  Expected: empty list

Test 4 — no_entry_for_weak_alternate_scenario
  Scenario has status=WEAK_ALTERNATE
  Expected: no entry candidate built
```

---

## Phase 7 — Hypothesis Manager (State Machine)

### Purpose
Track scenario status across multiple analysis runs. Store the previous `ScoredScenario` list, compare with new run, detect promotions/demotions/invalidations, and maintain a `HypothesisSnapshot` with history.

### Files to read before coding
- `src/main/java/com/dtech/ta/elliott/scenario/ScoredScenario.java` (Phase 5)
- `src/main/java/com/dtech/ta/elliott/scenario/ScenarioStatus.java` (Phase 5)
- `src/main/java/com/dtech/ta/elliott/WaveScenario.java`

### Package
`com.dtech.ta.elliott.hypothesis`

### Build: ScenarioTransition.java

```java
package com.dtech.ta.elliott.hypothesis;

public class ScenarioTransition {
    private String scenarioId;
    private ScenarioStatus fromStatus;
    private ScenarioStatus toStatus;
    private long timestamp;           // epoch millis
    private String reason;
    // constructors, getters
}
```

### Build: HypothesisSnapshot.java

```java
package com.dtech.ta.elliott.hypothesis;

public class HypothesisSnapshot {
    private String symbol;
    private String primaryTimeframe;
    private long snapshotTime;
    private List<ScoredScenario> scoredScenarios;
    private List<ScenarioTransition> transitions;  // changes vs previous snapshot
    private int relabelCount;   // increments each time leading scenario changes id
    private String leadingScenarioId;
    // constructors, getters
}
```

### Build: HypothesisManager.java

```java
package com.dtech.ta.elliott.hypothesis;

@Service
public class HypothesisManager {

    // In-memory store keyed by symbol+timeframe
    // For now Map<String, HypothesisSnapshot> is sufficient (no DB persistence in this phase)
    private final Map<String, HypothesisSnapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * Update the hypothesis state for a symbol after a new analysis run.
     * Computes transitions from previous snapshot.
     * @param symbol          instrument symbol
     * @param primaryTf       primary timeframe
     * @param newScenarios    freshly evaluated ScoredScenario list
     * @return updated HypothesisSnapshot
     */
    public HypothesisSnapshot update(String symbol, String primaryTf, List<ScoredScenario> newScenarios)

    /**
     * Get current snapshot for a symbol.
     * Returns null if no snapshot exists yet.
     */
    public HypothesisSnapshot get(String symbol, String primaryTf)

    /**
     * Clear the snapshot for a symbol (reset).
     */
    public void reset(String symbol, String primaryTf)
}
```

**Update logic:**
- key = symbol + "_" + primaryTf
- Get previous snapshot (may be null on first call)
- Build `List<ScenarioTransition>`: for each scenario in newScenarios, find previous by `WaveScenario.id` match, compare statuses
  - If status changed: create transition with fromStatus, toStatus, timestamp=now, reason="score change"
  - If scenario is new (not in previous): transition with fromStatus=null, toStatus=newStatus
  - If scenario disappeared (in previous but not new): fromStatus=previous, toStatus=INVALIDATED
- Compute `relabelCount`: if leading scenario id changed from previous, increment by 1
- Store new snapshot, return it

### Tests: HypothesisManagerTest.java

```
Test 1 — first_call_creates_snapshot
  Call update() with 2 scenarios, no prior snapshot exists
  Expected: snapshot created, transitions list has 2 entries (fromStatus=null)

Test 2 — promotion_detected
  Previous: scenario A = ACTIVE_ALTERNATE
  New: scenario A = LEADING
  Expected: transition fromStatus=ACTIVE_ALTERNATE, toStatus=LEADING

Test 3 — invalidation_detected
  Previous: scenario A = LEADING
  New: scenario A not in list (was invalidated)
  Expected: transition fromStatus=LEADING, toStatus=INVALIDATED

Test 4 — relabel_count_increments_on_leader_change
  Previous leading: scenario ID "S1"
  New leading: scenario ID "S2"
  Expected: relabelCount incremented by 1

Test 5 — relabel_count_stable_when_same_leader
  Previous leading: "S1", new leading: "S1"
  Expected: relabelCount unchanged

Test 6 — get_returns_null_before_first_update
  Call get() before any update()
  Expected: null returned, no exception

Test 7 — reset_clears_snapshot
  After update(), call reset()
  Then get() should return null
```

---

## Phase 8 — Controller Wiring + End-to-End Integration Test

### Purpose
Wire the `ElliottWaveAnalyzer` output into the copilot analysis flow, add confluence zone computation and entry candidates to the full analysis pipeline, and write an end-to-end integration test.

### Files to read before coding
- `src/main/java/com/dtech/kitecon/web/copilot/CopilotAnalysisController.java`
- `src/main/java/com/dtech/ta/elliott/ElliottWaveAnalyzer.java`
- `src/main/java/com/dtech/ta/elliott/ElliottWaveAnalysis.java`
- `src/main/java/com/dtech/ta/elliott/scenario/ScenarioStatusTracker.java` (Phase 5)
- `src/main/java/com/dtech/ta/elliott/trigger/EntryBuilder.java` (Phase 6)
- `src/main/java/com/dtech/ta/elliott/hypothesis/HypothesisManager.java` (Phase 7)

### Package
`com.dtech.ta.elliott` and `com.dtech.kitecon.web.copilot`

### Build: AdvancedElliottAnalysisResult.java

```java
package com.dtech.ta.elliott;

public class AdvancedElliottAnalysisResult {
    private ElliottWaveAnalysis waveAnalysis;
    private List<ConfluenceZone> confluenceZones;
    private List<ScoredScenario> scoredScenarios;
    private List<EntryCandidate> entryCandidates;
    private HypothesisSnapshot hypothesisSnapshot;
    private String promptSummary;    // combined narrative for AI reasoning pass
    // constructors, getters
}
```

### Build: AdvancedElliottService.java

```java
package com.dtech.ta.elliott;

@Service
public class AdvancedElliottService {

    // Inject all the services built in Phases 1–7:
    // SwingBuilder, MultiDegreePivotBuilder, FibLevelBuilder,
    // HorizontalSRBuilder, ConfluenceAggregator, DecisionZoneClassifier,
    // ScenarioStatusTracker, CandleTriggerDetector, EntryBuilder, HypothesisManager
    // Also inject: ElliottWaveAnalyzer (existing)

    /**
     * Run the full advanced Elliott analysis pipeline.
     * @param barSeriesByTf  map of timeframe → BarSeries (e.g. "1D" → series)
     * @param zigzagByTf     map of timeframe → List<ZigZagPoint> (pre-computed)
     * @param symbol         instrument symbol
     * @param primaryTf      primary timeframe for hypothesis manager
     * @return AdvancedElliottAnalysisResult
     */
    public AdvancedElliottAnalysisResult analyze(
        Map<String, BarSeries> barSeriesByTf,
        Map<String, List<ZigZagPoint>> zigzagByTf,
        String symbol,
        String primaryTf
    )
}
```

**Pipeline logic:**
1. Call `ElliottWaveAnalyzer.analyze()` → `ElliottWaveAnalysis`
2. For primary TF:
   a. Get medium-degree pivots via `MultiDegreePivotBuilder`
   b. Build swings via `SwingBuilder` from those pivots
   c. If swings non-empty, take last completed swing (swings.get(size-2)) as retracement base
   d. Build fib retracement levels from that swing (tolerance=0.005)
   e. Build S/R levels from medium pivots (tolerance=0.01)
   f. Combine all levels, run `ConfluenceAggregator`
   g. Run `DecisionZoneClassifier` with `waveAnalysis.topScenario()` as single-element list if non-null
3. Run `ScenarioStatusTracker.evaluate()` with `waveAnalysis.getScenarios()` and last close price
4. Detect triggers via `CandleTriggerDetector` (lookback=5)
5. Run `EntryBuilder.build()` with scored scenarios, triggers, decision zones, currentPrice
6. Update `HypothesisManager`
7. Build prompt summary = `waveAnalysis.toPromptSummary()` + zone summary + entry candidate summary
8. Return `AdvancedElliottAnalysisResult`

### Controller change: CopilotAnalysisController.java

Add a new endpoint `/api/copilot/analysis/full-elliott`:

```java
@PostMapping("/full-elliott")
public ResponseEntity<AdvancedElliottAnalysisResult> fullElliottAnalysis(
    @RequestParam String symbol,
    @RequestParam String primaryTimeframe,
    @RequestParam(defaultValue = "1D,1W") String timeframes
)
```

- Parse timeframe list
- Fetch bar series and zigzag pivots for each timeframe (reuse existing methods from the controller)
- Call `AdvancedElliottService.analyze()`
- Return result

### Integration Test: AdvancedElliottIntegrationTest.java

```
package: com.dtech.ta.elliott
class: AdvancedElliottIntegrationTest
annotations: @SpringBootTest, @ActiveProfiles("integration")

Test 1 — full_pipeline_runs_on_infy_daily
  Symbol: INFY, primaryTf: 1D, timeframes: [1D, 1W]
  Call AdvancedElliottService.analyze()
  Assertions:
  - result not null
  - waveAnalysis not null
  - confluenceZones not null (may be empty if no swings)
  - scoredScenarios not null (may be empty)
  - entryCandidates not null (may be empty)
  - hypothesisSnapshot not null
  - promptSummary not null and not blank

Test 2 — hypothesis_manager_persists_across_two_runs
  Call analyze() twice for same symbol
  Second call: hypothesisSnapshot.transitions should be non-null
  (transitions may be empty if scenarios unchanged)

Test 3 — scored_scenarios_have_valid_statuses
  All ScoredScenario.status values must be one of the ScenarioStatus enum values
  No null statuses allowed
```

---

## Summary: Phase Execution Order

| Phase | What It Builds | Depends On |
|---|---|---|
| 1 | Swing + SwingBuilder | ZigZagPoint (existing) |
| 2 | MultiDegreePivotSet + Builder | ZigZagParams (existing) |
| 3 | FibLevelBuilder + HorizontalSRBuilder | Swing (Phase 1), ZigZagPoint |
| 4 | ConfluenceAggregator + DecisionZoneClassifier | PriceLevel (Phase 3), WaveScenario (existing) |
| 5 | ScenarioStatus enum + ScenarioStatusTracker | WaveScenario (existing) |
| 6 | CandleTriggerDetector + EntryBuilder | ScoredScenario (Phase 5), ConfluenceZone (Phase 4) |
| 7 | HypothesisManager + state tracking | ScoredScenario (Phase 5) |
| 8 | AdvancedElliottService + controller wiring | All phases |
| 9 | Frontend — Elliott panel + API types + display | Phase 8 backend endpoint |

---

## Phase 9 — Frontend: Elliott Analysis Panel

### Purpose
Add a "Full Elliott" button to the existing `CopilotChartPanel`, wire it to the new `/api/copilot/analysis/full-elliott` backend endpoint, and render confluence zones, scored scenarios, entry candidates, and hypothesis state.

### Files to read before coding
- `ui/chart-draw-app/src/tradingview/copilotTypes.ts` (existing types)
- `ui/chart-draw-app/src/tradingview/copilotApi.ts` (existing API calls)
- `ui/chart-draw-app/src/tradingview/CopilotChartPanel.tsx` (existing panel)
- `ui/chart-draw-app/src/tradingview/ChartTabBar.tsx` (where buttons live)

### Package / path
All new files in: `ui/chart-draw-app/src/tradingview/`

---

### Step 9.1 — Add Types to copilotTypes.ts

Append these new interfaces to the bottom of `copilotTypes.ts`:

```typescript
// ─── Advanced Elliott Analysis Types ─────────────────────────────────────────

export type ScenarioStatus =
  | 'LEADING'
  | 'ACTIVE_ALTERNATE'
  | 'WEAK_ALTERNATE'
  | 'AWAITING_TRIGGER'
  | 'INVALIDATED'
  | 'COMPLETED';

export type TriggerType =
  | 'HAMMER'
  | 'SHOOTING_STAR'
  | 'BULLISH_ENGULFING'
  | 'BEARISH_ENGULFING'
  | 'LONG_LOWER_WICK_REJECTION'
  | 'LONG_UPPER_WICK_REJECTION'
  | 'NONE';

export interface ConfluenceZone {
  id: string;
  lowerPrice: number;
  upperPrice: number;
  midPrice: number;
  factorCount: number;
  factorDiversity: number;
  score: number;
  zoneType: string;        // "FIB_CLUSTER" | "SR_ONLY" | "MIXED" | "DECISION"
  explanation: string[];
}

export interface EntryCandidate {
  id: string;
  symbol: string;
  scenarioId: string;
  hypothesisId: string;
  bullish: boolean;
  entryStyle: string;      // "AGGRESSIVE" | "MODERATE" | "CONSERVATIVE"
  triggerType: TriggerType;
  entryPrice: number;
  stopLoss: number;
  target1: number;
  target2: number;
  riskRewardRatio: number;
  rationale: string[];
}

export interface ScenarioTransition {
  scenarioId: string;
  fromStatus: ScenarioStatus | null;
  toStatus: ScenarioStatus;
  timestamp: number;
  reason: string;
}

export interface HypothesisSnapshot {
  symbol: string;
  primaryTimeframe: string;
  snapshotTime: number;
  scoredScenarios: ScoredScenario[];
  transitions: ScenarioTransition[];
  relabelCount: number;
  leadingScenarioId: string | null;
}

export interface ScoredScenario {
  status: ScenarioStatus;
  totalScore: number;
  statusReasons: string[];
  scenario: {
    id: string;
    scenarioFamily: string;
    scenarioInvalidation: number | null;
    anchorTimeframe: string;
    hypotheses: Array<{
      id: string;
      currentPositionDescription: string;
      nextMajorMoveUp: boolean;
      totalScore: number;
      invalidationLevel: number;
      primaryTarget: { level: number; ratio: string; confidence: number } | null;
    }>;
  };
}

export interface AdvancedElliottResult {
  confluenceZones: ConfluenceZone[];
  scoredScenarios: ScoredScenario[];
  entryCandidates: EntryCandidate[];
  hypothesisSnapshot: HypothesisSnapshot | null;
  promptSummary: string;
}
```

---

### Step 9.2 — Add API call

In `copilotApi.ts` (or create `elliottApi.ts`), add:

```typescript
const API_BASE = '/api/copilot/analysis';

export async function runFullElliott(
  symbol: string,
  primaryTimeframe: string,
  timeframes: string   // comma-separated e.g. "1D,1W"
): Promise<AdvancedElliottResult> {
  const params = new URLSearchParams({ symbol, primaryTimeframe, timeframes });
  const res = await fetch(`${API_BASE}/full-elliott?${params}`, { method: 'POST' });
  if (!res.ok) throw new Error(`Elliott analysis failed: ${res.statusText}`);
  return res.json();
}
```

---

### Step 9.3 — Build ElliottPanel.tsx (new component)

New file: `ui/chart-draw-app/src/tradingview/ElliottPanel.tsx`

This is a read-only display component. Props:

```typescript
interface Props {
  result: AdvancedElliottResult | null;
  loading: boolean;
  error: string | null;
}
```

**Sections to render (top to bottom):**

#### Section A — Entry Candidates (most important — show first)
- If `entryCandidates.length === 0`: show grey text "No entry candidates"
- For each candidate, render a card:
  - Header: direction badge (green "LONG" / red "SHORT") + entryStyle chip + triggerType
  - Row: Entry `entryPrice` | SL `stopLoss` | T1 `target1` | R:R `riskRewardRatio.toFixed(2)`
  - Rationale: bullet list of `rationale` strings

#### Section B — Scored Scenarios
- Title: "Scenarios"
- For each `ScoredScenario`, render a row:
  - Status badge (color-coded, see STATUS_COLOR map below)
  - Score: `totalScore.toFixed(1)`
  - Label: `scenario.scenarioFamily`
  - Expand on click to show hypothesis descriptions and invalidation level
- STATUS_COLOR map:
  ```
  LEADING: '#1b5e20'
  ACTIVE_ALTERNATE: '#1565c0'
  WEAK_ALTERNATE: '#e65100'
  AWAITING_TRIGGER: '#37474f'
  INVALIDATED: '#b71c1c'
  COMPLETED: '#4a148c'
  ```

#### Section C — Confluence Zones
- Title: "Confluence Zones"
- Show only zones with `score >= 2` and `zoneType !== 'SR_ONLY'`
- For each zone, render a compact row:
  - Price range: `lowerPrice.toFixed(2)` — `upperPrice.toFixed(2)`
  - Score badge (yellow if DECISION, grey otherwise)
  - Factor count chip
  - Tooltip on hover: `explanation` strings joined with newline

#### Section D — Hypothesis State
- Title: "State Tracker"
- Show `relabelCount` (red if > 2), `leadingScenarioId`
- If `transitions.length > 0`, show last 3 transitions as a timeline:
  - Each: `fromStatus → toStatus` with reason

#### Section E — Prompt Summary (collapsible)
- Collapsed by default
- "Raw AI Prompt Summary" toggle
- Show `promptSummary` in a `<pre>` block (monospace, scroll overflow)

---

### Step 9.4 — Wire into CopilotChartPanel.tsx

**State to add:**
```typescript
const [elliottResult, setElliottResult] = useState<AdvancedElliottResult | null>(null);
const [elliottLoading, setElliottLoading] = useState(false);
const [elliottError, setElliottError] = useState<string | null>(null);
```

**Handler to add:**
```typescript
const handleFullElliott = useCallback(async () => {
  setElliottLoading(true);
  setElliottError(null);
  try {
    const primaryTf = timeframes[0] ?? '1D';
    const tfParam = timeframes.join(',');
    const result = await runFullElliott(symbol, primaryTf, tfParam);
    setElliottResult(result);
  } catch (e: any) {
    setElliottError(e.message ?? 'Elliott analysis failed');
  } finally {
    setElliottLoading(false);
  }
}, [symbol, timeframes]);
```

**Button to add** (alongside the existing Scan / Reason / Full buttons):
```tsx
<button
  onClick={handleFullElliott}
  disabled={elliottLoading}
  style={{ background: '#1a237e', color: '#fff', ... }}
>
  {elliottLoading ? 'Running...' : 'Elliott'}
</button>
```

**ElliottPanel to insert** (below existing hypothesis/observation sections):
```tsx
{elliottResult !== null && (
  <ElliottPanel
    result={elliottResult}
    loading={elliottLoading}
    error={elliottError}
  />
)}
```

---

### Step 9.5 — Test Scenarios

These are manual browser test scenarios (not automated):

**Test 1 — Happy path: Elliott button triggers analysis**
- Open app, select INFY on 1D chart
- Open Copilot panel
- Click "Elliott" button
- Expected: loading spinner appears, then Elliott panel renders below existing content
- Verify: no console errors, no blank screen

**Test 2 — Entry candidates display correctly**
- If backend returns an entry candidate (bullish, AGGRESSIVE, HAMMER)
- Expected: green "LONG" badge, "AGGRESSIVE" chip, entryPrice / SL / T1 / R:R visible
- Expected: rationale bullets visible

**Test 3 — Scenarios colored correctly**
- Backend returns LEADING, ACTIVE_ALTERNATE, INVALIDATED scenarios
- Expected: LEADING has dark green badge, INVALIDATED has dark red, ACTIVE_ALTERNATE has blue

**Test 4 — Decision zones visible**
- Backend returns zones with score >= 4, zoneType = "DECISION"
- Expected: zone shown with yellow score badge, price range visible

**Test 5 — Collapse prompt summary**
- Click "Raw AI Prompt Summary" toggle
- Expected: pre block expands/collapses without layout shift

**Test 6 — Error state**
- Backend returns 500
- Expected: red error message shown in panel, no crash

**Test 7 — Re-run updates result**
- Click Elliott once, get result
- Click again
- Expected: previous result replaced, no duplicates, loading state shown

**Test 8 — Empty result state**
- Backend returns empty confluenceZones, empty entryCandidates, empty scoredScenarios
- Expected: all sections show their "empty" placeholder text gracefully

---

## Summary: Phase Execution Order

1. **All classes are in Java**, not Python. Use `@Service` for Spring beans, `List<>` for collections.
2. **ta4j BarSeries:** access bars via `barSeries.getBar(index)`, last bar index = `barSeries.getEndIndex()`.
3. **ta4j Bar:** `bar.getOpenPrice().doubleValue()`, `bar.getHighPrice()`, `bar.getLowPrice()`, `bar.getClosePrice()`.
4. **ZigZagPoint:** has `getBarIndex()`, `getValue()`, `isHigh()`, `isLow()`.
5. **WaveScenario:** has `getScenarioInvalidation()` (Double), `getScenarioFamily()`, `getHypotheses()` (List<WaveHypothesis>).
6. **WaveHypothesis:** has `getId()`, `getTotalScore()`, `getInvalidationLevel()`, `getPrimaryTarget()` (FibTarget), `getTargetZones()`, `isNextMajorMoveUp()`.
7. **FibTarget:** has `getLevel()`, `getConfidence()`, `getRatio()`.
8. **Tests use JUnit 5:** `@Test`, `Assertions.assertEquals`, `Assertions.assertNotNull`, `Assertions.assertTrue`.
9. **Do NOT modify** any existing class in `com.dtech.ta.elliott` (ElliottWaveAnalyzer, WaveCounter, etc.).
10. **Each phase test class goes in:** `src/test/java/` mirroring the main source package path.
