# Second-Pass Scenario Filtering Engine — Build Plan
### For qwen2.5-coder:7b local model

Version: 1.0
Spec source: `docs/scenario_filtering_synthesis_java_spec.md`
This plan builds on the first-pass engine already implemented in `com.dtech.ta.elliott`.

---

## Architecture Summary

The first pass (`AdvancedElliottService`) produces `List<ScoredScenario>`.
Each `ScoredScenario` wraps a `WaveScenario` plus a `ScenarioStatus` and score.
The second pass takes that list, filters/ranks/compresses it, then sends a compact payload to AI for verification.

Full pipeline:
```
POST /api/analysis/full-elliott-verified
  → AdvancedElliottService.analyze()          [first pass]
  → ScoredScenarioAdapter.toScenarios()       [bridge]
  → DefaultScenarioFilterEngine.filter()       [second pass — this build plan]
  → ElliottVerificationService.verify()        [AI call]
  → VerifiedElliottResult (combined)           [returned to frontend]
```

---

## Existing Code You Will Reuse

All in `com.dtech.ta.elliott`:
- `WaveScenario` — has `direction` (ScenarioDirection enum), `alignedPatterns`, `conflictingPatterns`, `relevantPatterns` (List<PatternMatch>), `scenarioInvalidation` (double), `indicatorAlignment` (Map<String,String>)
- `WaveScenario.ScenarioDirection` — BULLISH_CONTINUATION, BEARISH_CONTINUATION, BULLISH_REVERSAL, BEARISH_REVERSAL, RANGE_RESOLUTION
- `ScoredScenario` — wraps WaveScenario + status (ScenarioStatus) + totalScore (double) + statusReasons (List<String>)
- `ScenarioStatus` — LEADING, ACTIVE_ALTERNATE, WEAK_ALTERNATE, AWAITING_TRIGGER, INVALIDATED, COMPLETED
- `PatternMatch` — type (PatternType), status (PatternStatus), confidence (double), description (String), support, resistance, target, invalidation (all Double)
- `PatternType` — DOUBLE_BOTTOM, DOUBLE_TOP, HEAD_AND_SHOULDERS, INVERTED_HEAD_AND_SHOULDERS, RISING_WEDGE, FALLING_WEDGE, ASCENDING_TRIANGLE, DESCENDING_TRIANGLE, SYMMETRICAL_TRIANGLE, ASCENDING_CHANNEL, DESCENDING_CHANNEL, HORIZONTAL_CHANNEL, BULL_FLAG, BEAR_FLAG, BULL_PENNANT, BEAR_PENNANT, BROADENING_TOP, BROADENING_BOTTOM, ELLIOTT_ZIGZAG, ELLIOTT_FLAT, ELLIOTT_EXPANDED_FLAT, CUP_AND_HANDLE, ROUNDING_BOTTOM, TRIPLE_BOTTOM, TRIPLE_TOP
- `PatternMatch.isBullish()` and `.isBearish()` — convenience methods already on PatternMatch

DO NOT re-create any of the above. Import and reuse.

---

## Build Order (12 phases)

| Phase | What to build | Test count |
|-------|---------------|------------|
| 0 | Bridge: Scenario record + ScoredScenarioAdapter | 3 |
| 1 | Enums + FilterConfig record | 0 |
| 2 | Domain records (all 9) | 0 |
| 3 | Public interfaces (all 9) | 0 |
| 4 | HardPruner + tests | 4 |
| 5 | ScenarioNormalizer + tests | 5 |
| 6 | SignatureBuilder + ScenarioDeduplicator + tests | 5 |
| 7 | ScenarioFamilyClassifier + tests | 6 |
| 8 | ConflictResolver + tests | 3 |
| 9 | FamilyScorer + tests | 4 |
| 10 | ScenarioCompressor + tests | 4 |
| 11 | Summary builders + tests | 4 |
| 12 | DefaultScenarioFilterEngine + integration tests | 4 |
| 13 | Pipeline wiring (ElliottVerificationService + endpoint) | — |

---

## Phase 0 — Bridge Layer

### File: `com.dtech.elliott.advanced.domain.scenario.Scenario`

```
package com.dtech.elliott.advanced.domain.scenario;

import com.dtech.ta.elliott.PatternMatch;
import com.dtech.ta.elliott.WaveScenario;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import java.util.List;
import java.util.Map;

public record Scenario(
    String id,
    String symbol,
    String anchorTimeframe,
    WaveScenario.ScenarioDirection direction,
    double invalidationLevel,
    String invalidationReason,
    ScenarioStatus status,
    double totalScore,
    List<PatternMatch> alignedPatterns,
    List<PatternMatch> conflictingPatterns,
    List<PatternMatch> relevantPatterns,
    Map<String, String> indicatorAlignment,
    List<WaveScenario.DecisionPoint> decisionPoints,
    List<String> statusReasons
) {}
```

### File: `com.dtech.elliott.advanced.domain.scenario.ScoredScenarioAdapter`

Method:
```
public static Scenario toScenario(ScoredScenario ss, String symbol, String anchorTimeframe)
```

Logic:
- Extract `WaveScenario ws = ss.getScenario()`
- If `ws == null`, throw `IllegalArgumentException("WaveScenario must not be null")`
- Return `new Scenario(ws.getId(), symbol, anchorTimeframe, ws.getDirection(), ws.getScenarioInvalidation(), ws.getInvalidationReason(), ss.getStatus(), ss.getTotalScore(), orEmpty(ws.getAlignedPatterns()), orEmpty(ws.getConflictingPatterns()), orEmpty(ws.getRelevantPatterns()), orEmpty(ws.getIndicatorAlignment()), orEmpty(ws.getDecisionPoints()), orEmpty(ss.getStatusReasons()))`
- Helper `private static <T> List<T> orEmpty(List<T> l)` → returns `l != null ? l : List.of()`
- Helper `private static <K,V> Map<K,V> orEmpty(Map<K,V> m)` → returns `m != null ? m : Map.of()`

Also add:
```
public static List<Scenario> toScenarios(List<ScoredScenario> list, String symbol, String anchorTimeframe)
```
Logic: if list null/empty return empty list, else map each with toScenario.

### Tests: `ScoredScenarioAdapterTest`

Test 1 — `null_wave_scenario_throws`:
- `ScoredScenario ss = ScoredScenario.builder().scenario(null).build()`
- `assertThrows(IllegalArgumentException.class, () -> ScoredScenarioAdapter.toScenario(ss, "NIFTY", "15m"))`

Test 2 — `maps_direction_and_invalidation`:
- Build WaveScenario with direction=BULLISH_CONTINUATION, scenarioInvalidation=1800.0
- Build ScoredScenario wrapping it, status=LEADING, totalScore=30
- Call `toScenario(ss, "NIFTY", "15m")`
- Assert `result.direction() == BULLISH_CONTINUATION`
- Assert `result.invalidationLevel() == 1800.0`
- Assert `result.status() == LEADING`

Test 3 — `null_list_returns_empty`:
- `assertTrue(ScoredScenarioAdapter.toScenarios(null, "X", "15m").isEmpty())`

---

## Phase 1 — Enums and Config

### Enum: `com.dtech.elliott.advanced.common.enums.Direction`
Values: UP, DOWN, NEUTRAL

### Enum: `com.dtech.elliott.advanced.common.enums.ExpectedMoveType`
Values: CONTINUATION_UP, CONTINUATION_DOWN, REVERSAL_UP, REVERSAL_DOWN, BREAKOUT_PENDING, COMPRESSION, UNKNOWN

### Enum: `com.dtech.elliott.advanced.common.enums.StructureFamily`
Values: IMPULSIVE, CORRECTIVE, TERMINAL, COMPRESSIVE, AMBIGUOUS

### Enum: `com.dtech.elliott.advanced.scenario.filter.domain.ScenarioFamilyType`
Values: BULLISH_CONTINUATION_AFTER_CORRECTION, BULLISH_CONTINUATION_AFTER_COMPRESSION, BEARISH_REVERSAL_AFTER_TERMINAL_PUSH, BEARISH_BREAKDOWN_AFTER_COMPRESSION, SIDEWAYS_COMPRESSION_BEFORE_BREAKOUT, ONGOING_CORRECTIVE_COMPLEXITY, MOTIVE_EXTENSION_STILL_ACTIVE, LOW_CONVICTION_REVERSAL_SEED, NO_TRADE_NOISE

### Record: `com.dtech.elliott.advanced.scenario.filter.config.FilterConfig`

Fields (all have defaults shown):
```
double hardPruneStructuralScoreFloor       // default 0.20
double weakPruneStructuralScoreFloor       // default 0.35
double ambiguityCeilingForLeadingScenario  // default 0.60
double contradictionPenaltyWeight          // default 0.3
double ambiguityPenaltyWeight              // default 0.25
double structuralWeight                    // default 0.35
double confluenceWeight                    // default 0.25
double momentumWeight                      // default 0.15
double tradeUtilityWeight                  // default 0.15
double triggerReadinessWeight              // default 0.10
double invalidationBucketSize              // default 50.0
int    maxMembersPerCluster                // default 10
int    maxFamiliesBeforeCompression        // default 8
int    maxActiveAlternates                 // default 2
int    maxWeakAlternates                   // default 1
boolean keepContrarianAlternate            // default true
boolean suppressNoTradeNoiseFamilies       // default true
```

Add static factory method:
```
public static FilterConfig defaults()
```
Returns a new FilterConfig with all the above defaults.

---

## Phase 2 — Domain Records

All in package `com.dtech.elliott.advanced.scenario.filter.domain`

### 1. `NormalizedScenario` record
Fields:
```
String sourceScenarioId
String symbol
String anchorTimeframe
Direction directionalBias              // from common.enums
ExpectedMoveType expectedMoveType      // from common.enums
StructureFamily dominantStructureFamily // from common.enums
List<String> supportingStructureTypes
List<String> supportingPatternTypes
double primaryInvalidationLevel
double invalidationTolerance
double targetReferenceLevel
double confluenceScore
double structuralScore
double momentumScore
double ambiguityScore
double tradeUtilityScore
boolean decisionZoneNearby
boolean triggerEligible
ScenarioStatus sourceStatus            // from com.dtech.ta.elliott.scenario
Map<String, Double> scoreComponents
List<String> explanation
List<String> reasonCodes
```

### 2. `ScenarioSignature` record
Fields:
```
String symbol
String anchorTimeframe
Direction directionalBias
ExpectedMoveType expectedMoveType
StructureFamily dominantStructureFamily
long roundedInvalidationBucket
boolean decisionZoneNearby
boolean triggerEligible
```

### 3. `ScenarioCluster` record
Fields:
```
ScenarioSignature signature
List<NormalizedScenario> members
double aggregatedStructuralScore
double aggregatedTradeUtilityScore
double aggregatedConfluenceScore
double aggregatedMomentumScore
List<String> mergedSupportingPatterns
List<String> mergedSupportingStructures
List<String> mergedReasonCodes
List<String> mergedExplanation
```

### 4. `FamilyScore` record
Fields:
```
double structuralStrength
double confluenceStrength
double momentumAlignment
double ambiguityPenalty
double contradictionPenalty
double tradeUtility
double triggerReadiness
double finalRankScore
```

### 5. `ScenarioFamilyCandidate` record
Fields:
```
String id
String symbol
String anchorTimeframe
ScenarioFamilyType familyType
Direction directionalBias
List<ScenarioCluster> sourceClusters
List<String> supportingStructures
List<String> supportingPatterns
List<String> contradictingPatterns
double primaryInvalidationLevel
double confirmationLevel
double projectedTargetReference
boolean decisionZoneNearby
boolean triggerEligible
boolean tradableNow
ScenarioStatus status
FamilyScore score
List<String> explanation
List<String> reasonCodes
Map<String, Object> metadata
```

### 6. `ScenarioConflictSet` record
Fields:
```
String symbol
String anchorTimeframe
List<ScenarioFamilyCandidate> bullishFamilies
List<ScenarioFamilyCandidate> bearishFamilies
List<ScenarioFamilyCandidate> neutralFamilies
String dominantConflictMode
List<String> explanation
```

### 7. `HumanResearchSummary` record
Fields:
```
String symbol
String anchorTimeframe
String marketStateSummary
List<String> leadingScenarioSummary
List<String> alternateScenarioSummary
List<String> actionHandlingNotes
List<String> invalidationNotes
```

### 8. `ReasoningPayloadCompression` record
Fields:
```
String symbol
String anchorTimeframe
Map<String, Object> leadingScenario
List<Map<String, Object>> alternates
List<Map<String, Object>> decisionZones
List<Map<String, Object>> triggerWatchList
List<String> contradictionSummary
```

### 9. `FilteredScenarioSet` record
Fields:
```
String symbol
String anchorTimeframe
ScenarioFamilyCandidate leadingScenario      // nullable
List<ScenarioFamilyCandidate> activeAlternates
List<ScenarioFamilyCandidate> weakAlternates
List<ScenarioFamilyCandidate> invalidatedFamilies
ScenarioConflictSet conflictSet
HumanResearchSummary humanSummary
ReasoningPayloadCompression reasoningPayloadCompression
```

---

## Phase 3 — Public Interfaces

All in `com.dtech.elliott.advanced.scenario.filter.api`

```java
// 1
public interface ScenarioFilterEngine {
    FilteredScenarioSet filter(List<Scenario> rawScenarios, FilterConfig config);
}

// 2 — in prune package
public interface HardPruner {
    List<Scenario> prune(List<Scenario> rawScenarios, FilterConfig config);
}

// 3 — in normalize package
public interface ScenarioNormalizer {
    List<NormalizedScenario> normalize(List<Scenario> scenarios, FilterConfig config);
}

// 4 — in dedupe package
public interface SignatureBuilder {
    ScenarioSignature build(NormalizedScenario scenario, FilterConfig config);
}

// 5 — in dedupe package
public interface ScenarioDeduplicator {
    List<ScenarioCluster> deduplicate(List<NormalizedScenario> scenarios, FilterConfig config);
}

// 6 — in classify package
public interface ScenarioFamilyClassifier {
    List<ScenarioFamilyCandidate> classify(List<ScenarioCluster> clusters, FilterConfig config);
}

// 7 — in conflict package
public interface ConflictResolver {
    ScenarioConflictSet resolve(List<ScenarioFamilyCandidate> families, FilterConfig config);
}

// 8 — in score package
public interface FamilyScorer {
    List<ScenarioFamilyCandidate> score(List<ScenarioFamilyCandidate> families, FilterConfig config);
}

// 9 — in compress package
public interface ScenarioCompressor {
    FilteredScenarioSet compress(
        List<ScenarioFamilyCandidate> scoredFamilies,
        ScenarioConflictSet conflictSet,
        FilterConfig config
    );
}

// 10 — in summary package
public interface HumanResearchSummaryBuilder {
    HumanResearchSummary build(FilteredScenarioSet filteredScenarioSet);
}

// 11 — in summary package
public interface ReasoningPayloadCompressionBuilder {
    ReasoningPayloadCompression build(FilteredScenarioSet filteredScenarioSet);
}
```

No tests in this phase — these are interfaces only.

---

## Phase 4 — HardPruner

### File: `com.dtech.elliott.advanced.scenario.filter.prune.DefaultHardPruner`

Implements `HardPruner`.

Logic in `prune(List<Scenario> rawScenarios, FilterConfig config)`:
- if rawScenarios null or empty: return empty list
- for each scenario, check all prune conditions:
  1. `scenario.status() == ScenarioStatus.INVALIDATED` → prune
  2. `scenario.status() == ScenarioStatus.COMPLETED` → prune
  3. `scenario.invalidationLevel() == 0.0` (or NaN) → prune (no usable invalidation)
  4. `scenario.alignedPatterns().isEmpty() && scenario.conflictingPatterns().isEmpty() && scenario.relevantPatterns().isEmpty()` → prune (no structures and no patterns)
  5. `scenario.totalScore() / 100.0 < config.hardPruneStructuralScoreFloor()` → prune (totalScore is 0–100, floor is 0.0–1.0 fraction)
  6. `scenario.direction() == null` → prune
- Keep all scenarios that pass every check
- Log count: `log.debug("HardPruner: {} → {} after hard prune", rawScenarios.size(), result.size())`

Use SLF4J logger: `private static final Logger log = LoggerFactory.getLogger(DefaultHardPruner.class);`

### Tests: `DefaultHardPrunerTest`

Helpers: use `com.dtech.elliott.advanced.domain.scenario.Scenario` record constructors.

Helper method for building a minimal valid Scenario:
```
private Scenario valid() {
    return new Scenario("S1", "NIFTY", "15m",
        WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
        1800.0, "wave structure", ScenarioStatus.ACTIVE_ALTERNATE, 50.0,
        List.of(), List.of(), List.of(), Map.of(), List.of(), List.of());
}
```

Test 1 — `invalidated_scenario_pruned`:
- Build scenario with `status=INVALIDATED`, all else valid
- `assertEquals(0, pruner.prune(List.of(s), config).size())`

Test 2 — `zero_invalidation_pruned`:
- Build scenario with `invalidationLevel=0.0`
- `assertEquals(0, pruner.prune(List.of(s), config).size())`

Test 3 — `low_score_pruned`:
- Build scenario with `totalScore=10.0` (10/100 = 0.10, below floor 0.20)
- `assertEquals(0, pruner.prune(List.of(s), config).size())`

Test 4 — `valid_scenario_kept`:
- Use the valid() helper scenario above
- `assertEquals(1, pruner.prune(List.of(s), config).size())`

---

## Phase 5 — ScenarioNormalizer

### File: `com.dtech.elliott.advanced.scenario.filter.normalize.DefaultScenarioNormalizer`

Implements `ScenarioNormalizer`.

Logic in `normalize(List<Scenario> scenarios, FilterConfig config)`:
- if null or empty: return empty list
- for each scenario, call `normalizeOne(scenario, config)`

Logic in `normalizeOne(Scenario s, FilterConfig config)`:
1. `Direction directionalBias = resolveDirection(s.direction())`
   - BULLISH_CONTINUATION → UP
   - BULLISH_REVERSAL → UP
   - BEARISH_CONTINUATION → DOWN
   - BEARISH_REVERSAL → DOWN
   - RANGE_RESOLUTION → NEUTRAL

2. `ExpectedMoveType expectedMoveType = resolveExpectedMove(s.direction())`
   - BULLISH_CONTINUATION → CONTINUATION_UP
   - BEARISH_CONTINUATION → CONTINUATION_DOWN
   - BULLISH_REVERSAL → REVERSAL_UP
   - BEARISH_REVERSAL → REVERSAL_DOWN
   - RANGE_RESOLUTION → BREAKOUT_PENDING

3. `StructureFamily dominantStructureFamily = resolveStructureFamily(s.relevantPatterns())`
   - if relevantPatterns contains any PatternType in [ELLIOTT_ZIGZAG, ELLIOTT_FLAT, ELLIOTT_EXPANDED_FLAT, DOUBLE_BOTTOM, DOUBLE_TOP, HEAD_AND_SHOULDERS, INVERTED_HEAD_AND_SHOULDERS, TRIPLE_BOTTOM, TRIPLE_TOP, CUP_AND_HANDLE, ROUNDING_BOTTOM] → CORRECTIVE
   - if relevantPatterns contains [RISING_WEDGE, FALLING_WEDGE] → TERMINAL
   - if relevantPatterns contains [SYMMETRICAL_TRIANGLE, ASCENDING_TRIANGLE, DESCENDING_TRIANGLE, ASCENDING_CHANNEL, DESCENDING_CHANNEL, HORIZONTAL_CHANNEL] → COMPRESSIVE
   - if direction is BULLISH_CONTINUATION or BEARISH_CONTINUATION and no corrective/compressive/terminal → IMPULSIVE
   - otherwise → AMBIGUOUS

4. `double structuralScore = Math.min(s.totalScore() / 100.0, 1.0)` (normalize to 0..1)

5. `double confluenceScore` — check decisionPoints:
   - if `s.decisionPoints()` is not empty: `0.5 + 0.1 * Math.min(s.decisionPoints().size(), 5)`
   - else: `0.3`

6. `double momentumScore` — check indicatorAlignment:
   - count values containing "BULLISH" or "bearish" (case-insensitive) in `s.indicatorAlignment()`
   - `momentumScore = 0.3 + 0.14 * bullishCount` if direction UP, else similar logic
   - simplified: `0.5` if indicatorAlignment is empty, else `(bullishOrBearishAligned / total)` clamped to 0..1

7. `boolean decisionZoneNearby = !s.decisionPoints().isEmpty()`

8. `boolean triggerEligible`:
   - true if status is LEADING or ACTIVE_ALTERNATE
   - and at least one aligned pattern with confidence > 0.5

9. `double ambiguityScore`:
   - count conflicting patterns; `ambiguityScore = Math.min(s.conflictingPatterns().size() * 0.15, 0.9)`

10. `double tradeUtilityScore = triggerEligible ? 0.7 : 0.3`

11. `List<String> supportingPatternTypes` = deduplicated list of `pattern.getType().name()` from `s.alignedPatterns()`

12. `List<String> supportingStructureTypes` = deduplicated list of `pattern.getType().name()` from `s.relevantPatterns()` where pattern is not in alignedPatterns

13. `List<String> explanation` = deduplicated `s.statusReasons()`

14. `List<String> reasonCodes` = list of formatted strings like `"DIRECTION:" + s.direction().name()`, `"STATUS:" + s.status().name()`

15. `double targetReferenceLevel`:
    - find first aligned pattern with target != null: use that
    - else `0.0`

16. `double invalidationTolerance = config.invalidationBucketSize() * 0.5`

Return `new NormalizedScenario(s.id(), s.symbol(), s.anchorTimeframe(), directionalBias, expectedMoveType, dominantStructureFamily, supportingStructureTypes, supportingPatternTypes, s.invalidationLevel(), invalidationTolerance, targetReferenceLevel, confluenceScore, structuralScore, momentumScore, ambiguityScore, tradeUtilityScore, decisionZoneNearby, triggerEligible, s.status(), Map.of("structural", structuralScore, "confluence", confluenceScore, "momentum", momentumScore, "ambiguity", ambiguityScore), explanation, reasonCodes)`

### Tests: `DefaultScenarioNormalizerTest`

Test 1 — `bullish_continuation_maps_to_UP`:
- Build scenario with direction=BULLISH_CONTINUATION
- `assertEquals(Direction.UP, result.get(0).directionalBias())`

Test 2 — `range_resolution_maps_to_NEUTRAL_and_BREAKOUT_PENDING`:
- Build scenario with direction=RANGE_RESOLUTION
- `assertEquals(Direction.NEUTRAL, result.directionalBias())`
- `assertEquals(ExpectedMoveType.BREAKOUT_PENDING, result.expectedMoveType())`

Test 3 — `structure_family_for_zigzag`:
- Build scenario with relevantPatterns containing a PatternMatch of type ELLIOTT_ZIGZAG
- `assertEquals(StructureFamily.CORRECTIVE, result.dominantStructureFamily())`

Test 4 — `compressive_structure_for_triangle`:
- Build scenario with relevantPatterns containing SYMMETRICAL_TRIANGLE
- `assertEquals(StructureFamily.COMPRESSIVE, result.dominantStructureFamily())`

Test 5 — `null_input_returns_empty`:
- `assertTrue(normalizer.normalize(null, config).isEmpty())`

---

## Phase 6 — Signature Builder + Deduplicator

### File: `com.dtech.elliott.advanced.scenario.filter.dedupe.DefaultSignatureBuilder`

Implements `SignatureBuilder`.

Logic in `build(NormalizedScenario s, FilterConfig config)`:
```
long bucket = Math.round(s.primaryInvalidationLevel() / config.invalidationBucketSize());
return new ScenarioSignature(
    s.symbol(),
    s.anchorTimeframe(),
    s.directionalBias(),
    s.expectedMoveType(),
    s.dominantStructureFamily(),
    bucket,
    s.decisionZoneNearby(),
    s.triggerEligible()
);
```

### File: `com.dtech.elliott.advanced.scenario.filter.dedupe.DefaultScenarioDeduplicator`

Implements `ScenarioDeduplicator`.

Fields: `private final SignatureBuilder signatureBuilder;`
Constructor: `DefaultScenarioDeduplicator(SignatureBuilder signatureBuilder)`

Logic in `deduplicate(List<NormalizedScenario> scenarios, FilterConfig config)`:
1. if null/empty: return empty list
2. Build map: `Map<ScenarioSignature, List<NormalizedScenario>> grouped`
   - for each scenario: compute signature, add to group
3. For each group, create a `ScenarioCluster`:
   - cap members to `config.maxMembersPerCluster()`
   - `aggregatedStructuralScore = members.stream().mapToDouble(n -> n.structuralScore()).average().orElse(0.0)`
   - `aggregatedConfluenceScore = members.stream().mapToDouble(n -> n.confluenceScore()).average().orElse(0.0)`
   - `aggregatedMomentumScore = members.stream().mapToDouble(n -> n.momentumScore()).average().orElse(0.0)`
   - `aggregatedTradeUtilityScore = members.stream().mapToDouble(n -> n.tradeUtilityScore()).average().orElse(0.0)`
   - `mergedSupportingPatterns` = deduplicated union of all members' supportingPatternTypes
   - `mergedSupportingStructures` = deduplicated union of all members' supportingStructureTypes
   - `mergedReasonCodes` = deduplicated union of all members' reasonCodes
   - `mergedExplanation` = deduplicated union of all members' explanation
4. Return list of clusters, sorted by `aggregatedStructuralScore` descending

Helper for deduplication: `private <T> List<T> dedup(List<List<T>> lists)` → flatten and deduplicate via LinkedHashSet.

### Tests: `DefaultSignatureBuilderTest`

Test 1 — `same_scenario_produces_same_signature`:
- Create two identical NormalizedScenario objects
- Both must produce equal ScenarioSignature
- `assertEquals(sig1, sig2)`

Test 2 — `different_direction_produces_different_signature`:
- Two NormalizedScenario with different directionalBias
- `assertNotEquals(sig1, sig2)`

Test 3 — `invalidation_bucket_is_rounded`:
- scenario with `primaryInvalidationLevel=1824.0`, `invalidationBucketSize=50.0`
- `bucket = Math.round(1824.0 / 50.0) = 36`
- `assertEquals(36L, sig.roundedInvalidationBucket())`

### Tests: `DefaultScenarioDeduplicatorTest`

Test 4 — `same_signature_grouped_into_one_cluster`:
- Build two NormalizedScenario with identical signature inputs
- `assertEquals(1, deduplicator.deduplicate(list, config).size())`

Test 5 — `merged_cluster_has_combined_patterns`:
- Scenario 1 has supportingPatternTypes=["FALLING_WEDGE"]
- Scenario 2 has supportingPatternTypes=["ASCENDING_TRIANGLE"]
- After dedup into one cluster, `mergedSupportingPatterns` contains both

---

## Phase 7 — ScenarioFamilyClassifier

### File: `com.dtech.elliott.advanced.scenario.filter.classify.DefaultScenarioFamilyClassifier`

Implements `ScenarioFamilyClassifier`.

Fields:
```
private static final AtomicInteger idCounter = new AtomicInteger(0);
```

Logic in `classify(List<ScenarioCluster> clusters, FilterConfig config)`:
- for each cluster, call `classifyCluster(cluster, config)` → ScenarioFamilyCandidate
- return list

Logic in `classifyCluster(ScenarioCluster cluster, FilterConfig config)`:
1. Get signature = cluster.signature()
2. Get patterns = cluster.mergedSupportingPatterns()
3. Determine `ScenarioFamilyType familyType`:

   **Rule 1 — Compression**: if containsCompressionPatterns(patterns) AND signature.expectedMoveType() == BREAKOUT_PENDING → SIDEWAYS_COMPRESSION_BEFORE_BREAKOUT

   **Rule 2 — Bullish after compression**: if signature.directionalBias() == UP AND containsBullishCompressionPatterns(patterns) AND signature.expectedMoveType() == CONTINUATION_UP → BULLISH_CONTINUATION_AFTER_COMPRESSION

   **Rule 3 — Bearish breakdown**: if signature.directionalBias() == DOWN AND containsBearishCompressionPatterns(patterns) → BEARISH_BREAKDOWN_AFTER_COMPRESSION

   **Rule 4 — Bullish after correction**: if signature.dominantStructureFamily() == CORRECTIVE AND signature.directionalBias() == UP AND signature.expectedMoveType() == CONTINUATION_UP → BULLISH_CONTINUATION_AFTER_CORRECTION

   **Rule 5 — Bearish reversal terminal**: if containsTerminalPatterns(patterns) AND signature.directionalBias() == DOWN AND signature.expectedMoveType() == REVERSAL_DOWN → BEARISH_REVERSAL_AFTER_TERMINAL_PUSH

   **Rule 6 — Ongoing corrective**: if signature.dominantStructureFamily() == CORRECTIVE AND signature.directionalBias() == NEUTRAL → ONGOING_CORRECTIVE_COMPLEXITY

   **Rule 7 — Low conviction reversal seed**: if containsBullishReversalSeeds(patterns) OR containsBearishReversalSeeds(patterns), AND !signature.triggerEligible() → LOW_CONVICTION_REVERSAL_SEED

   **Default**: NO_TRADE_NOISE

4. Determine `Direction directionalBias = signature.directionalBias()`
5. `double primaryInvalidation` = members average primaryInvalidationLevel
6. Build FamilyScore with all zeros (scoring happens in Phase 9)
7. `ScenarioStatus status` = map from first member's sourceStatus, or ACTIVE_ALTERNATE if none
8. `String id = "FAM-" + idCounter.incrementAndGet()`
9. Build and return ScenarioFamilyCandidate

Helper methods in `ClassificationHelper` (static utility class in same package):
```
public static boolean containsCompressionPatterns(List<String> patterns)
  → patterns contains any of: SYMMETRICAL_TRIANGLE, ASCENDING_TRIANGLE, DESCENDING_TRIANGLE, ASCENDING_CHANNEL, DESCENDING_CHANNEL, HORIZONTAL_CHANNEL

public static boolean containsBullishCompressionPatterns(List<String> patterns)
  → patterns contains any of: FALLING_WEDGE, ASCENDING_TRIANGLE, BULL_FLAG, BULL_PENNANT

public static boolean containsBearishCompressionPatterns(List<String> patterns)
  → patterns contains any of: RISING_WEDGE, DESCENDING_TRIANGLE, BEAR_FLAG, BEAR_PENNANT

public static boolean containsTerminalPatterns(List<String> patterns)
  → patterns contains any of: RISING_WEDGE, HEAD_AND_SHOULDERS, DOUBLE_TOP, TRIPLE_TOP, BROADENING_TOP

public static boolean containsBullishReversalSeeds(List<String> patterns)
  → patterns contains any of: DOUBLE_BOTTOM, INVERTED_HEAD_AND_SHOULDERS, ROUNDING_BOTTOM, TRIPLE_BOTTOM

public static boolean containsBearishReversalSeeds(List<String> patterns)
  → patterns contains any of: DOUBLE_TOP, HEAD_AND_SHOULDERS, TRIPLE_TOP
```

### Tests: `DefaultScenarioFamilyClassifierTest`

Setup helper: `private ScenarioCluster cluster(Direction dir, ExpectedMoveType emt, StructureFamily sf, String... patterns)`
- Build a minimal cluster with the given signature fields and merged patterns

Test 1 — `triangle_wedge_compression_family`:
- cluster(NEUTRAL, BREAKOUT_PENDING, COMPRESSIVE, "SYMMETRICAL_TRIANGLE", "ASCENDING_CHANNEL")
- `assertEquals(ScenarioFamilyType.SIDEWAYS_COMPRESSION_BEFORE_BREAKOUT, result.familyType())`

Test 2 — `falling_wedge_bullish_continuation`:
- cluster(UP, CONTINUATION_UP, COMPRESSIVE, "FALLING_WEDGE")
- `assertEquals(ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_COMPRESSION, result.familyType())`

Test 3 — `corrective_up_family`:
- cluster(UP, CONTINUATION_UP, CORRECTIVE, "ELLIOTT_ZIGZAG")
- `assertEquals(ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION, result.familyType())`

Test 4 — `terminal_bearish_family`:
- cluster(DOWN, REVERSAL_DOWN, TERMINAL, "RISING_WEDGE", "HEAD_AND_SHOULDERS")
- `assertEquals(ScenarioFamilyType.BEARISH_REVERSAL_AFTER_TERMINAL_PUSH, result.familyType())`

Test 5 — `low_conviction_seed`:
- cluster with directional UP but triggerEligible=false, patterns=["DOUBLE_BOTTOM"]
- `assertEquals(ScenarioFamilyType.LOW_CONVICTION_REVERSAL_SEED, result.familyType())`

Test 6 — `empty_clusters_returns_empty`:
- `assertTrue(classifier.classify(List.of(), config).isEmpty())`

---

## Phase 8 — Conflict Resolver

### File: `com.dtech.elliott.advanced.scenario.filter.conflict.DefaultConflictResolver`

Implements `ConflictResolver`.

Logic in `resolve(List<ScenarioFamilyCandidate> families, FilterConfig config)`:
1. if null/empty families: return empty ScenarioConflictSet
2. Partition:
   - `bullishFamilies` = families where `directionalBias == UP`
   - `bearishFamilies` = families where `directionalBias == DOWN`
   - `neutralFamilies` = families where `directionalBias == NEUTRAL`
3. Determine `dominantConflictMode`:
   - if bullishFamilies not empty AND bearishFamilies not empty → "BULLISH_VS_BEARISH"
   - else if neutralFamilies.size() > bullishFamilies.size() + bearishFamilies.size() → "TRENDING_VS_COMPRESSIVE"
   - else if families stream any MOTIVE_EXTENSION_STILL_ACTIVE alongside ONGOING_CORRECTIVE_COMPLEXITY → "CONTINUATION_VS_TERMINAL"
   - else → "LOW_CONVICTION_NOISE"
4. Build explanation list:
   - if BULLISH_VS_BEARISH: add "Bullish continuation family exists but bearish terminal family remains active"
   - if TRENDING_VS_COMPRESSIVE: add "Compression family blocks directional confidence"
   - always add count summary string
5. Return `new ScenarioConflictSet(symbol, anchorTimeframe, bullishFamilies, bearishFamilies, neutralFamilies, dominantConflictMode, explanation)`

Note: symbol and anchorTimeframe should come from the first family's fields (if not empty).

### Tests: `DefaultConflictResolverTest`

Setup helper: `private ScenarioFamilyCandidate family(String id, Direction dir, ScenarioFamilyType type)`

Test 1 — `bullish_and_bearish_creates_conflict`:
- Pass [bullish family, bearish family]
- `assertEquals("BULLISH_VS_BEARISH", result.dominantConflictMode())`
- `assertEquals(1, result.bullishFamilies().size())`
- `assertEquals(1, result.bearishFamilies().size())`

Test 2 — `neutral_only_creates_compressive_mode`:
- Pass [2 neutral families, 0 bullish, 0 bearish]
- `assertEquals("TRENDING_VS_COMPRESSIVE", result.dominantConflictMode())`

Test 3 — `empty_input_returns_empty_conflict_set`:
- `assertTrue(resolver.resolve(List.of(), config).bullishFamilies().isEmpty())`

---

## Phase 9 — FamilyScorer

### File: `com.dtech.elliott.advanced.scenario.filter.score.DefaultFamilyScorer`

Implements `FamilyScorer`.

Logic in `score(List<ScenarioFamilyCandidate> families, FilterConfig config)`:
- for each family, compute FamilyScore and return a new ScenarioFamilyCandidate with the score set

Logic in `scoreFamily(ScenarioFamilyCandidate family, FilterConfig config)`:
1. `double structuralStrength`:
   - average `aggregatedStructuralScore` of all sourceClusters
   - if no clusters: 0.3

2. `double confluenceStrength`:
   - average `aggregatedConfluenceScore` of all sourceClusters
   - if no clusters: 0.3

3. `double momentumAlignment`:
   - average `aggregatedMomentumScore` of all sourceClusters
   - if no clusters: 0.3

4. `double ambiguityPenalty`:
   - sum of `aggregatedStructuralScore` variance across clusters (simplified: std deviation / mean, clamped 0..1)
   - if only one cluster: 0.1
   - if family is NO_TRADE_NOISE: 0.8

5. `double contradictionPenalty`:
   - `contradictingPatterns.size() * 0.1` clamped to 0..1

6. `double tradeUtility`:
   - base = `family.tradableNow() ? 0.8 : 0.3`
   - +0.1 if `family.decisionZoneNearby()`
   - +0.1 if `family.triggerEligible()`
   - -0.2 if familyType == LOW_CONVICTION_REVERSAL_SEED or NO_TRADE_NOISE
   - clamp to 0..1

7. `double triggerReadiness`:
   - 0.8 if triggerEligible AND decisionZoneNearby
   - 0.5 if triggerEligible only
   - 0.2 if decisionZoneNearby only
   - 0.1 otherwise

8. `double finalRankScore`:
   ```
   finalRankScore =
     config.structuralWeight()     * structuralStrength
   + config.confluenceWeight()     * confluenceStrength
   + config.momentumWeight()       * momentumAlignment
   + config.tradeUtilityWeight()   * tradeUtility
   + config.triggerReadinessWeight() * triggerReadiness
   - config.ambiguityPenaltyWeight() * ambiguityPenalty
   - config.contradictionPenaltyWeight() * contradictionPenalty
   ```

Build `FamilyScore` record and return updated ScenarioFamilyCandidate using a builder or copy-with-score approach.
Since ScenarioFamilyCandidate is a record, you must use a builder pattern or manual copy constructor to create a new record with the score field set.

**IMPORTANT**: Since Java records are immutable, use this pattern to rebuild with score:
```java
return new ScenarioFamilyCandidate(
    family.id(), family.symbol(), family.anchorTimeframe(), family.familyType(),
    family.directionalBias(), family.sourceClusters(), family.supportingStructures(),
    family.supportingPatterns(), family.contradictingPatterns(),
    family.primaryInvalidationLevel(), family.confirmationLevel(),
    family.projectedTargetReference(), family.decisionZoneNearby(),
    family.triggerEligible(), family.tradableNow(), family.status(),
    computedScore,  // the new FamilyScore
    family.explanation(), family.reasonCodes(), family.metadata()
);
```

### Tests: `DefaultFamilyScorerTest`

Setup helper: `private ScenarioFamilyCandidate family(boolean triggerEligible, boolean decisionZone, int contradictingCount, ScenarioFamilyType type)`
- Build with one cluster that has structural=0.7, confluence=0.6, momentum=0.5
- Build with given triggerEligible, decisionZoneNearby, and contradictingPatterns list of given size

Test 1 — `high_confluence_low_ambiguity_ranks_higher`:
- Family A: confluenceStrength=0.8, ambiguityPenalty low
- Family B: confluenceStrength=0.3, ambiguityPenalty high
- `assertTrue(scoreA.finalRankScore() > scoreB.finalRankScore())`

Test 2 — `contradiction_penalty_reduces_score`:
- Family with 0 contradicting patterns vs 4 contradicting patterns
- `assertTrue(scoreWithContradictions.finalRankScore() < scoreWithout.finalRankScore())`

Test 3 — `trigger_ready_improves_trade_utility`:
- family(triggerEligible=true, decisionZone=true, ...) vs (false, false, ...)
- Score with trigger > score without trigger

Test 4 — `no_trade_noise_gets_low_score`:
- family of type NO_TRADE_NOISE
- `assertTrue(result.score().finalRankScore() < 0.3)`

---

## Phase 10 — ScenarioCompressor

### File: `com.dtech.elliott.advanced.scenario.filter.compress.DefaultScenarioCompressor`

Implements `ScenarioCompressor`.

Logic in `compress(List<ScenarioFamilyCandidate> scoredFamilies, ScenarioConflictSet conflictSet, FilterConfig config)`:
1. if null/empty families: return empty-state FilteredScenarioSet (all fields empty/null, humanSummary and reasoningPayload null — those are filled by orchestrator)
2. Sort `scoredFamilies` by finalRankScore descending (stable sort)
   Tie-breaker order (from spec section 11):
   a. higher structuralStrength
   b. higher confluenceStrength
   c. lower ambiguityPenalty
   d. higher tradeUtility
   e. lexicographic id
3. Optionally filter out NO_TRADE_NOISE if `config.suppressNoTradeNoiseFamilies()`
4. Separate invalidated:
   - `invalidatedFamilies` = families where status == INVALIDATED
   - `activeFamilies` = rest
5. Select `leadingScenario`:
   - if activeFamilies.isEmpty(): null
   - else if top family ambiguityScore > config.ambiguityCeilingForLeadingScenario(): keep as ACTIVE_ALTERNATE, no clear LEADING → leadingScenario = null
   - else: top family = leadingScenario
6. Select `activeAlternates`:
   - from remaining activeFamilies (excluding leading), take up to `config.maxActiveAlternates()`
   - if `config.keepContrarianAlternate()` and no contrarian in current alternates: add first contrarian (opposite direction to leading) from remaining families
7. Select `weakAlternates`:
   - from remaining families not yet selected, take up to `config.maxWeakAlternates()`
8. Return `new FilteredScenarioSet(symbol, anchorTimeframe, leadingScenario, activeAlternates, weakAlternates, invalidatedFamilies, conflictSet, null, null)`
   Note: humanSummary and reasoningPayload are null here, filled by orchestrator.

Helper: `private String symbolFrom(List<ScenarioFamilyCandidate> families)` → first non-null symbol from families

### Tests: `DefaultScenarioCompressorTest`

Helper: build ScenarioFamilyCandidate with a FamilyScore that has a specific finalRankScore. Since it's a record, build it manually.

Test 1 — `highest_ranked_becomes_leading`:
- families with finalRankScore: [0.8, 0.5, 0.3]
- `assertEquals(0.8, result.leadingScenario().score().finalRankScore())`

Test 2 — `alternates_capped_at_max`:
- 5 families (all active), maxActiveAlternates=2
- `assertEquals(2, result.activeAlternates().size())`

Test 3 — `invalidated_families_in_separate_bucket`:
- 2 active families + 1 with status=INVALIDATED
- `assertEquals(1, result.invalidatedFamilies().size())`
- `assertEquals(1, result.activeAlternates().size())`  (2 active → 1 leading + 1 alternate)

Test 4 — `no_trade_noise_suppressed`:
- families: [BULLISH score=0.6, NO_TRADE_NOISE score=0.3], suppressNoTradeNoise=true
- `assertEquals(1, result.activeAlternates().size() + (result.leadingScenario() != null ? 1 : 0))`
- NO_TRADE_NOISE family not present in output

---

## Phase 11 — Summary Builders

### File: `com.dtech.elliott.advanced.scenario.filter.summary.DefaultHumanResearchSummaryBuilder`

Implements `HumanResearchSummaryBuilder`.

Logic in `build(FilteredScenarioSet set)`:
1. `marketStateSummary`:
   - if leadingScenario != null: "Leading scenario is {familyType} with {direction} bias. {conflictMode if conflict}"
   - else: "Market remains in {conflictSet.dominantConflictMode()} — no clear leading scenario."
2. `leadingScenarioSummary` (3–5 bullets):
   - if leading != null:
     - "Direction: {leading.directionalBias()}"
     - "Family: {leading.familyType()}"
     - "Invalidation: {leading.primaryInvalidationLevel()}"
     - "Trigger eligible: {leading.triggerEligible()}"
     - "Patterns: {leading.supportingPatterns()}"
3. `alternateScenarioSummary`:
   - for each alternate: one bullet: "{alt.id()} — {alt.familyType()} ({alt.directionalBias()})"
4. `actionHandlingNotes`:
   - if triggerEligible: "Watch for trigger near decision zone"
   - if decisionZoneNearby: "Price is near a decision zone"
   - if leading null: "No high-conviction setup — stand aside or watch"
   - add "Alternates: " + count string
5. `invalidationNotes`:
   - for each invalidated family: "{fam.id()} invalidated"
   - if leading != null: "Leading invalidated if price crosses " + leading.primaryInvalidationLevel()

Return `new HumanResearchSummary(symbol, anchorTimeframe, marketStateSummary, leadingScenarioSummary, alternateScenarioSummary, actionHandlingNotes, invalidationNotes)`

---

### File: `com.dtech.elliott.advanced.scenario.filter.summary.DefaultReasoningPayloadCompressionBuilder`

Implements `ReasoningPayloadCompressionBuilder`.

Logic in `build(FilteredScenarioSet set)`:
1. `leadingScenario` map: if leading != null, build map with: `id`, `familyType`, `direction`, `invalidation`, `triggerEligible`, `patterns`, `score` (finalRankScore)
2. `alternates` list: for each activeAlternate, build map with same fields (5 fields each)
3. `decisionZones`: if leading != null, extract any decision zone info from sourceClusters' mergedExplanation that mentions "decision"
   - simplified: `List.of(Map.of("note", "See leading scenario for decision zone context"))`
4. `triggerWatchList`: families where triggerEligible=true → map each to `{"familyId": id, "direction": dir, "pattern": first supportingPattern}`
5. `contradictionSummary`: extract from conflictSet.explanation()

Return new ReasoningPayloadCompression.

### Tests: `DefaultSummaryBuildersTest`

Test 1 — `human_summary_includes_market_state`:
- Build FilteredScenarioSet with leading scenario (UP, BULLISH_CONTINUATION_AFTER_CORRECTION)
- `assertNotNull(summary.marketStateSummary())`
- `assertFalse(summary.marketStateSummary().isBlank())`

Test 2 — `human_summary_has_leading_scenario_bullets`:
- `assertFalse(summary.leadingScenarioSummary().isEmpty())`

Test 3 — `human_summary_handles_null_leading`:
- FilteredScenarioSet with leadingScenario=null
- `assertTrue(summary.marketStateSummary().contains("no clear leading"))`

Test 4 — `reasoning_payload_is_compact`:
- Build full FilteredScenarioSet with 1 leading + 2 alternates
- `assertNotNull(payload.leadingScenario())`
- `assertEquals(2, payload.alternates().size())`

---

## Phase 12 — Orchestration + Integration Tests

### File: `com.dtech.elliott.advanced.scenario.filter.orchestration.DefaultScenarioFilterEngine`

Implements `ScenarioFilterEngine`.

Annotate with `@Service` (Spring).

Fields (all injected via constructor):
```java
private final HardPruner hardPruner;
private final ScenarioNormalizer normalizer;
private final ScenarioDeduplicator deduplicator;
private final ScenarioFamilyClassifier classifier;
private final ConflictResolver conflictResolver;
private final FamilyScorer scorer;
private final ScenarioCompressor compressor;
private final HumanResearchSummaryBuilder humanSummaryBuilder;
private final ReasoningPayloadCompressionBuilder payloadBuilder;
```

Logic in `filter(List<Scenario> rawScenarios, FilterConfig config)`:
```
1. if rawScenarios null: rawScenarios = List.of()
2. List<Scenario> pruned = hardPruner.prune(rawScenarios, config)
3. List<NormalizedScenario> normalized = normalizer.normalize(pruned, config)
4. List<ScenarioCluster> clusters = deduplicator.deduplicate(normalized, config)
5. List<ScenarioFamilyCandidate> families = classifier.classify(clusters, config)
6. ScenarioConflictSet conflictSet = conflictResolver.resolve(families, config)
7. List<ScenarioFamilyCandidate> scored = scorer.score(families, config)
8. FilteredScenarioSet compressed = compressor.compress(scored, conflictSet, config)
9. HumanResearchSummary humanSummary = humanSummaryBuilder.build(compressed)
10. ReasoningPayloadCompression payload = payloadBuilder.build(compressed)
11. Rebuild compressed with humanSummary and payload:
    return new FilteredScenarioSet(
        compressed.symbol(), compressed.anchorTimeframe(),
        compressed.leadingScenario(), compressed.activeAlternates(),
        compressed.weakAlternates(), compressed.invalidatedFamilies(),
        conflictSet, humanSummary, payload
    )
```

### Spring Bean Config: `ScenarioFilterEngineConfig`

```java
package com.dtech.elliott.advanced.scenario.filter.orchestration;

@Configuration
public class ScenarioFilterEngineConfig {

    @Bean
    public SignatureBuilder signatureBuilder() { return new DefaultSignatureBuilder(); }

    @Bean
    public HardPruner hardPruner() { return new DefaultHardPruner(); }

    @Bean
    public ScenarioNormalizer scenarioNormalizer() { return new DefaultScenarioNormalizer(); }

    @Bean
    public ScenarioDeduplicator scenarioDeduplicator(SignatureBuilder sb) {
        return new DefaultScenarioDeduplicator(sb);
    }

    @Bean
    public ScenarioFamilyClassifier scenarioFamilyClassifier() { return new DefaultScenarioFamilyClassifier(); }

    @Bean
    public ConflictResolver conflictResolver() { return new DefaultConflictResolver(); }

    @Bean
    public FamilyScorer familyScorer() { return new DefaultFamilyScorer(); }

    @Bean
    public ScenarioCompressor scenarioCompressor() { return new DefaultScenarioCompressor(); }

    @Bean
    public HumanResearchSummaryBuilder humanResearchSummaryBuilder() { return new DefaultHumanResearchSummaryBuilder(); }

    @Bean
    public ReasoningPayloadCompressionBuilder reasoningPayloadCompressionBuilder() { return new DefaultReasoningPayloadCompressionBuilder(); }

    @Bean
    public ScenarioFilterEngine scenarioFilterEngine(
        HardPruner hp, ScenarioNormalizer sn, ScenarioDeduplicator sd,
        ScenarioFamilyClassifier sfc, ConflictResolver cr,
        FamilyScorer fs, ScenarioCompressor sc,
        HumanResearchSummaryBuilder hsb, ReasoningPayloadCompressionBuilder rpb
    ) {
        return new DefaultScenarioFilterEngine(hp, sn, sd, sfc, cr, fs, sc, hsb, rpb);
    }
}
```

### Tests: `DefaultScenarioFilterEngineIntegrationTest`

Use real implementations (no mocks). Build scenarios using ScoredScenario + WaveScenario helpers.

Helper method:
```java
private ScoredScenario scored(WaveScenario.ScenarioDirection dir, double invalidation,
                               ScenarioStatus status, double score, PatternType... patterns)
```

Build WaveScenario with given direction, invalidation, and aligned patterns, then wrap in ScoredScenario.

The engine is created manually (not Spring context):
```java
private ScenarioFilterEngine engine;

@BeforeEach
void setUp() {
    var sigBuilder = new DefaultSignatureBuilder();
    engine = new DefaultScenarioFilterEngine(
        new DefaultHardPruner(),
        new DefaultScenarioNormalizer(),
        new DefaultScenarioDeduplicator(sigBuilder),
        new DefaultScenarioFamilyClassifier(),
        new DefaultConflictResolver(),
        new DefaultFamilyScorer(),
        new DefaultScenarioCompressor(),
        new DefaultHumanResearchSummaryBuilder(),
        new DefaultReasoningPayloadCompressionBuilder()
    );
}
```

Test 1 — `compression_case`:
- Input: 3 scenarios, all RANGE_RESOLUTION, different invalidations near same bucket, patterns: SYMMETRICAL_TRIANGLE, ASCENDING_CHANNEL, DESCENDING_CHANNEL
- `assertNotNull(result.leadingScenario())`
- `assertEquals(ScenarioFamilyType.SIDEWAYS_COMPRESSION_BEFORE_BREAKOUT, result.leadingScenario().familyType())`

Test 2 — `bullish_continuation_case`:
- Input: 2 BULLISH_CONTINUATION scenarios with FALLING_WEDGE + ASCENDING_TRIANGLE patterns, score=70
- Leading scenario should be BULLISH_CONTINUATION_AFTER_COMPRESSION or BULLISH_CONTINUATION_AFTER_CORRECTION
- `assertNotNull(result.leadingScenario())`
- `assertEquals(Direction.UP, result.leadingScenario().directionalBias())`

Test 3 — `all_invalidated_returns_empty_leading`:
- Input: 2 scenarios with status=INVALIDATED
- All pruned before processing
- `assertNull(result.leadingScenario())`
- `assertNotNull(result.humanSummary())`

Test 4 — `null_input_returns_valid_empty_result`:
- `var result = engine.filter(null, FilterConfig.defaults())`
- `assertNotNull(result)`
- `assertNull(result.leadingScenario())`

---

## Phase 13 — Pipeline Wiring

### File: `com.dtech.ta.elliott.ElliottVerificationService`

Purpose: Orchestrates full pipeline from ScoredScenario list → filtered → AI verified.

Fields:
```java
private final ScenarioFilterEngine filterEngine;
private final CopilotAIService aiService;         // existing, in com.dtech.algo.copilot
private final AIResponseParser responseParser;    // existing
```

Method:
```java
public VerifiedElliottResult verify(
    List<ScoredScenario> scoredScenarios,
    String symbol,
    String anchorTimeframe,
    Long userId,
    FilterConfig config
)
```

Logic:
1. `List<Scenario> raw = ScoredScenarioAdapter.toScenarios(scoredScenarios, symbol, anchorTimeframe)`
2. `FilteredScenarioSet filtered = filterEngine.filter(raw, config)`
3. Build AI instructions string:
   ```
   String instructions = "You are an Elliott Wave analyst. Review this filtered scenario set and confirm or challenge the leading scenario. Return your assessment as JSON with fields: confirmed (boolean), adjustedLeading (string family type or null), reasoning (string), confidence (0.0-1.0)."
   ```
4. Convert `filtered.reasoningPayloadCompression()` to JSON string using Jackson ObjectMapper
5. `String aiResponse = aiService.call(userId, instructions, payloadJson)`
6. Build and return `VerifiedElliottResult`

### Record: `com.dtech.ta.elliott.VerifiedElliottResult`
```java
public record VerifiedElliottResult(
    FilteredScenarioSet filteredScenarioSet,
    String aiRawResponse,
    boolean aiConfirmed,
    String aiReasoning,
    double aiConfidence
)
```

Parse aiRawResponse: look for JSON fields using simple string contains or Jackson:
- `aiConfirmed = response contains "\"confirmed\":true"`
- `aiReasoning = extract reasoning field (or use raw response)`
- `aiConfidence = extract confidence field (or 0.5 default)`

If aiService throws or returns null/empty:
- `aiConfirmed = false`, `aiReasoning = "AI verification unavailable"`, `aiConfidence = 0.0`

### Controller endpoint in `CopilotAnalysisController`

Add alongside existing `POST /api/analysis/full-elliott`:

```java
@PostMapping("/full-elliott-verified")
public ResponseEntity<VerifiedElliottResult> runFullElliottVerified(
    @RequestBody ElliottAnalysisRequest request,
    @AuthenticationPrincipal UserDetails userDetails
)
```

Logic:
1. Load BarSeries and ZigZag data same as existing full-elliott endpoint
2. `AdvancedElliottAnalysisResult firstPass = advancedElliottService.analyze(...)`
3. `List<ScoredScenario> scored = firstPass.getScoredScenarios()`
4. Get userId from userDetails (or use 1L as default for now)
5. `VerifiedElliottResult result = elliottVerificationService.verify(scored, symbol, primaryTimeframe, userId, FilterConfig.defaults())`
6. Return `ResponseEntity.ok(result)`

Register `ElliottVerificationService` as a `@Service` bean.

### Frontend additions

In `copilotTypes.ts`, append:
```typescript
export interface VerifiedElliottResult {
  filteredScenarioSet: FilteredScenarioSet;
  aiRawResponse: string;
  aiConfirmed: boolean;
  aiReasoning: string;
  aiConfidence: number;
}

export interface FilteredScenarioSet {
  symbol: string;
  anchorTimeframe: string;
  leadingScenario: ScenarioFamilyCandidate | null;
  activeAlternates: ScenarioFamilyCandidate[];
  weakAlternates: ScenarioFamilyCandidate[];
  invalidatedFamilies: ScenarioFamilyCandidate[];
  humanSummary: HumanResearchSummary;
}

export interface ScenarioFamilyCandidate {
  id: string;
  familyType: string;
  directionalBias: 'UP' | 'DOWN' | 'NEUTRAL';
  supportingPatterns: string[];
  primaryInvalidationLevel: number;
  triggerEligible: boolean;
  tradableNow: boolean;
  score: FamilyScore;
  explanation: string[];
}

export interface FamilyScore {
  structuralStrength: number;
  confluenceStrength: number;
  finalRankScore: number;
}

export interface HumanResearchSummary {
  marketStateSummary: string;
  leadingScenarioSummary: string[];
  alternateScenarioSummary: string[];
  actionHandlingNotes: string[];
  invalidationNotes: string[];
}
```

In `copilotApi.ts`, add:
```typescript
export async function runFullElliottVerified(
  symbol: string,
  primaryTimeframe: string,
  timeframes: string[]
): Promise<VerifiedElliottResult> {
  const res = await fetch('/api/analysis/full-elliott-verified', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ symbol, primaryTimeframe, timeframes })
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}
```

In `ElliottPanel.tsx`, add a "Verified" section below the existing sections:
- Show `humanSummary.marketStateSummary` in a highlighted box
- Show leading scenario family type + direction badge + final rank score
- Show AI confirmation status (green tick / red cross + confidence %)
- Show `aiReasoning` in a collapsible block

---

## Package Layout Summary

```
com.dtech.elliott.advanced
├── common.enums
│   ├── Direction.java
│   ├── ExpectedMoveType.java
│   └── StructureFamily.java
├── domain.scenario
│   ├── Scenario.java                        (record — bridge input)
│   └── ScoredScenarioAdapter.java
└── scenario.filter
    ├── api
    │   └── ScenarioFilterEngine.java
    ├── config
    │   └── FilterConfig.java
    ├── domain
    │   ├── NormalizedScenario.java
    │   ├── ScenarioSignature.java
    │   ├── ScenarioCluster.java
    │   ├── ScenarioFamilyType.java
    │   ├── FamilyScore.java
    │   ├── ScenarioFamilyCandidate.java
    │   ├── ScenarioConflictSet.java
    │   ├── HumanResearchSummary.java
    │   ├── ReasoningPayloadCompression.java
    │   └── FilteredScenarioSet.java
    ├── prune
    │   ├── HardPruner.java (interface)
    │   └── DefaultHardPruner.java
    ├── normalize
    │   ├── ScenarioNormalizer.java (interface)
    │   └── DefaultScenarioNormalizer.java
    ├── dedupe
    │   ├── SignatureBuilder.java (interface)
    │   ├── ScenarioDeduplicator.java (interface)
    │   ├── DefaultSignatureBuilder.java
    │   └── DefaultScenarioDeduplicator.java
    ├── classify
    │   ├── ScenarioFamilyClassifier.java (interface)
    │   ├── DefaultScenarioFamilyClassifier.java
    │   └── ClassificationHelper.java
    ├── conflict
    │   ├── ConflictResolver.java (interface)
    │   └── DefaultConflictResolver.java
    ├── score
    │   ├── FamilyScorer.java (interface)
    │   └── DefaultFamilyScorer.java
    ├── compress
    │   ├── ScenarioCompressor.java (interface)
    │   └── DefaultScenarioCompressor.java
    ├── summary
    │   ├── HumanResearchSummaryBuilder.java (interface)
    │   ├── ReasoningPayloadCompressionBuilder.java (interface)
    │   ├── DefaultHumanResearchSummaryBuilder.java
    │   └── DefaultReasoningPayloadCompressionBuilder.java
    └── orchestration
        ├── DefaultScenarioFilterEngine.java
        └── ScenarioFilterEngineConfig.java

com.dtech.ta.elliott (existing, extend):
├── ElliottVerificationService.java          (NEW)
├── VerifiedElliottResult.java               (NEW record)
└── (existing: AdvancedElliottService, AdvancedElliottAnalysisResult, etc.)
```

---

## Notes for Code Model

1. **Java version**: Java 17+. Records available. Use `record` keyword for all domain objects. Use Lombok only on non-record classes.

2. **Imports**:
   - `com.dtech.ta.elliott.WaveScenario` (already exists)
   - `com.dtech.ta.elliott.scenario.ScoredScenario` (already exists)
   - `com.dtech.ta.elliott.scenario.ScenarioStatus` (already exists)
   - `com.dtech.ta.elliott.PatternMatch` (already exists)
   - `com.dtech.ta.elliott.PatternType` (already exists)
   - `org.slf4j.Logger`, `org.slf4j.LoggerFactory` (already on classpath)
   - `org.springframework.stereotype.Service`
   - `org.springframework.context.annotation.Configuration`, `@Bean`

3. **Existing ScenarioStatus enum values**: LEADING, ACTIVE_ALTERNATE, WEAK_ALTERNATE, AWAITING_TRIGGER, INVALIDATED, COMPLETED — use these in all status checks.

4. **PatternType** values available (copy from `PatternType.java`): DOUBLE_BOTTOM, DOUBLE_TOP, TRIPLE_BOTTOM, TRIPLE_TOP, HEAD_AND_SHOULDERS, INVERTED_HEAD_AND_SHOULDERS, CUP_AND_HANDLE, ROUNDING_BOTTOM, ASCENDING_TRIANGLE, DESCENDING_TRIANGLE, SYMMETRICAL_TRIANGLE, ASCENDING_CHANNEL, DESCENDING_CHANNEL, HORIZONTAL_CHANNEL, BULL_FLAG, BEAR_FLAG, BULL_PENNANT, BEAR_PENNANT, RISING_WEDGE, FALLING_WEDGE, BROADENING_TOP, BROADENING_BOTTOM, ELLIOTT_ZIGZAG, ELLIOTT_FLAT, ELLIOTT_EXPANDED_FLAT

5. **Do not use `new ArrayList<>()` mutable lists in record fields**. Use `List.copyOf()` or ensure lists passed in are immutable. For test helpers, `List.of(...)` is fine.

6. **Tie-breaking in sort**: Use `Comparator.comparingDouble()` chaining with `.thenComparingDouble()` and `.thenComparing()` for stable sorts.

7. **Each phase must compile independently** before moving to the next. Phases 0, 1, 2, 3 have no dependencies on each other and can be done in sequence 0→1→2→3. Each later phase depends on all prior phases.

8. **Test pattern**: use JUnit 5 (`@Test`, `@BeforeEach`). No Mockito needed — all implementations are simple enough to use directly. `FilterConfig.defaults()` is your standard test config.
