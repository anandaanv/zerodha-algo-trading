# Scenario Filtering and Synthesis Engine
## Detailed Java Code-Level Specification

Version: 1.0  
Target language: Java 21  
Base package: `com.dtech.elliott.advanced`  
Module focus: second-pass filtering, deduplication, family synthesis, conflict ranking, and human-facing compression of raw Elliott/pattern extraction output.

---

# 1. Purpose

This module is the **second pass** after the raw extraction engine.

Its job is to convert a noisy set of raw structure and pattern candidates into a small, coherent, ranked set of **scenario families** that are useful for:
- human research consumption
- downstream AI reasoning
- trade-preparation logic
- historical validation and ranking

This module must be deterministic.

It must not:
- perform broker execution
- place orders
- guess a single “truth”
- ignore alternates that are still structurally alive

It must:
- kill invalid scenarios
- merge duplicates
- compress equivalent scenarios
- rank conflicts
- emit a small scenario set with explanations

---

# 2. What It Consumes

This module consumes output from earlier modules:

- `StructureCandidate`
- `PatternCandidate`
- `ConfluenceZone`
- `Scenario` (raw scenario objects assembled from stage 1)
- optional `MomentumProfile`
- optional `EntryCandidate` seeds or trigger hints
- higher timeframe context summaries

It assumes the first pass has already:
- extracted wave possibilities
- detected chart patterns
- built decision zones
- created raw scenarios

This engine does **not** detect Elliott waves directly.

---

# 3. What It Produces

This module produces:

1. `NormalizedScenario`
2. `ScenarioSignature`
3. `ScenarioCluster`
4. `ScenarioFamilyCandidate`
5. `ScenarioConflictSet`
6. `FilteredScenarioSet`
7. `HumanResearchSummary`
8. `ReasoningPayloadCompression`

The output must contain:
- 1 leading scenario family
- 1–2 active alternates
- optional weak alternate / watchlist scenario
- all invalidations
- all major triggers
- all supporting and contradicting evidence

---

# 4. High-Level Pipeline

```text
Raw Scenario List
    ↓
[1] Hard Pruning
    ↓
[2] Normalization
    ↓
[3] Deduplication / Signature Grouping
    ↓
[4] Scenario Clustering
    ↓
[5] Family Classification
    ↓
[6] Conflict Resolution
    ↓
[7] Ranking and Trade-Relevance Scoring
    ↓
[8] Final Compression
    ↓
[9] Human Summary + Reasoning Payload
```

---

# 5. Package Structure

All code under:

`com.dtech.elliott.advanced.scenario.filter`

Recommended package layout:

```text
com.dtech.elliott.advanced.scenario.filter
├── api
├── config
├── domain
├── normalize
├── prune
├── dedupe
├── cluster
├── classify
├── conflict
├── score
├── compress
├── summary
└── orchestration
```

### 5.1 Package purpose

#### `api`
Public interfaces.

#### `config`
Configuration records/classes for filtering thresholds and weights.

#### `domain`
Second-pass domain objects.

#### `normalize`
Scenario normalization logic.

#### `prune`
Hard-prune and weak-prune logic.

#### `dedupe`
Signature construction and duplicate merging.

#### `cluster`
Cluster equivalent scenarios into broader buckets.

#### `classify`
Map clusters into user-facing scenario families.

#### `conflict`
Resolve competing family directions and statuses.

#### `score`
Score family strength and trade relevance.

#### `compress`
Reduce scenario count to final presentation set.

#### `summary`
Produce user-facing and AI-facing summaries.

#### `orchestration`
Coordinator service for the full second pass.

---

# 6. Domain Objects

All under `com.dtech.elliott.advanced.scenario.filter.domain`

Use Java records where possible.

---

## 6.1 NormalizedScenario

Purpose:
Standardize raw stage-1 scenarios into a comparable shape.

```java
package com.dtech.elliott.advanced.scenario.filter.domain;

import java.util.List;
import java.util.Map;
import com.dtech.elliott.advanced.common.enums.Direction;
import com.dtech.elliott.advanced.common.enums.ExpectedMoveType;
import com.dtech.elliott.advanced.common.enums.ScenarioStatus;
import com.dtech.elliott.advanced.common.enums.StructureFamily;

public record NormalizedScenario(
        String sourceScenarioId,
        String symbol,
        String anchorTimeframe,
        Direction directionalBias,
        ExpectedMoveType expectedMoveType,
        StructureFamily dominantStructureFamily,
        List<String> supportingStructureTypes,
        List<String> supportingPatternTypes,
        double primaryInvalidationLevel,
        double invalidationTolerance,
        double targetReferenceLevel,
        double confluenceScore,
        double structuralScore,
        double momentumScore,
        double ambiguityScore,
        double tradeUtilityScore,
        boolean decisionZoneNearby,
        boolean triggerEligible,
        ScenarioStatus sourceStatus,
        Map<String, Double> scoreComponents,
        List<String> explanation,
        List<String> reasonCodes
) {}
```

### Construction rules
- must always have one primary invalidation level
- must always have one normalized expected move type
- must reduce multi-label pattern lists to standardized buckets
- must not preserve duplicate identical explanation strings

---

## 6.2 ScenarioSignature

Purpose:
Define whether multiple normalized scenarios represent the same market idea.

```java
public record ScenarioSignature(
        String symbol,
        String anchorTimeframe,
        Direction directionalBias,
        ExpectedMoveType expectedMoveType,
        StructureFamily dominantStructureFamily,
        long roundedInvalidationBucket,
        boolean decisionZoneNearby,
        boolean triggerEligible
) {}
```

### Construction rules
- `roundedInvalidationBucket` should be computed by rounding invalidation to configurable tolerance
- signatures must be stable across repeated runs
- no floating-point raw comparison allowed

---

## 6.3 ScenarioCluster

Purpose:
Group normalized scenarios with same or near-equivalent implications.

```java
import java.util.List;

public record ScenarioCluster(
        ScenarioSignature signature,
        List<NormalizedScenario> members,
        double aggregatedStructuralScore,
        double aggregatedTradeUtilityScore,
        double aggregatedConfluenceScore,
        double aggregatedMomentumScore,
        List<String> mergedSupportingPatterns,
        List<String> mergedSupportingStructures,
        List<String> mergedReasonCodes,
        List<String> mergedExplanation
) {}
```

---

## 6.4 ScenarioFamilyType

Create enum under `common.enums` or local filter enums.

```java
public enum ScenarioFamilyType {
    BULLISH_CONTINUATION_AFTER_CORRECTION,
    BULLISH_CONTINUATION_AFTER_COMPRESSION,
    BEARISH_REVERSAL_AFTER_TERMINAL_PUSH,
    BEARISH_BREAKDOWN_AFTER_COMPRESSION,
    SIDEWAYS_COMPRESSION_BEFORE_BREAKOUT,
    ONGOING_CORRECTIVE_COMPLEXITY,
    MOTIVE_EXTENSION_STILL_ACTIVE,
    LOW_CONVICTION_REVERSAL_SEED,
    NO_TRADE_NOISE
}
```

---

## 6.5 ScenarioFamilyCandidate

Purpose:
User-facing and AI-facing market state family.

```java
import java.util.List;
import java.util.Map;
import com.dtech.elliott.advanced.common.enums.Direction;
import com.dtech.elliott.advanced.common.enums.ScenarioStatus;

public record ScenarioFamilyCandidate(
        String id,
        String symbol,
        String anchorTimeframe,
        ScenarioFamilyType familyType,
        Direction directionalBias,
        List<ScenarioCluster> sourceClusters,
        List<String> supportingStructures,
        List<String> supportingPatterns,
        List<String> contradictingPatterns,
        double primaryInvalidationLevel,
        double confirmationLevel,
        double projectedTargetReference,
        boolean decisionZoneNearby,
        boolean triggerEligible,
        boolean tradableNow,
        ScenarioStatus status,
        FamilyScore score,
        List<String> explanation,
        List<String> reasonCodes,
        Map<String, Object> metadata
) {}
```

---

## 6.6 FamilyScore

```java
public record FamilyScore(
        double structuralStrength,
        double confluenceStrength,
        double momentumAlignment,
        double ambiguityPenalty,
        double contradictionPenalty,
        double tradeUtility,
        double triggerReadiness,
        double finalRankScore
) {}
```

---

## 6.7 ScenarioConflictSet

Purpose:
Represent conflicts between family candidates.

```java
import java.util.List;

public record ScenarioConflictSet(
        String symbol,
        String anchorTimeframe,
        List<ScenarioFamilyCandidate> bullishFamilies,
        List<ScenarioFamilyCandidate> bearishFamilies,
        List<ScenarioFamilyCandidate> neutralFamilies,
        String dominantConflictMode,
        List<String> explanation
) {}
```

Possible conflict modes:
- `BULLISH_VS_BEARISH`
- `TRENDING_VS_COMPRESSIVE`
- `CONTINUATION_VS_TERMINAL`
- `LOW_CONVICTION_NOISE`

---

## 6.8 FilteredScenarioSet

Purpose:
Final output of second pass.

```java
import java.util.List;

public record FilteredScenarioSet(
        String symbol,
        String anchorTimeframe,
        ScenarioFamilyCandidate leadingScenario,
        List<ScenarioFamilyCandidate> activeAlternates,
        List<ScenarioFamilyCandidate> weakAlternates,
        List<ScenarioFamilyCandidate> invalidatedFamilies,
        ScenarioConflictSet conflictSet,
        HumanResearchSummary humanSummary,
        ReasoningPayloadCompression reasoningPayloadCompression
) {}
```

---

## 6.9 HumanResearchSummary

```java
import java.util.List;

public record HumanResearchSummary(
        String symbol,
        String anchorTimeframe,
        String marketStateSummary,
        List<String> leadingScenarioSummary,
        List<String> alternateScenarioSummary,
        List<String> actionHandlingNotes,
        List<String> invalidationNotes
) {}
```

---

## 6.10 ReasoningPayloadCompression

Purpose:
Small, high-signal payload for AI model.

```java
import java.util.List;
import java.util.Map;

public record ReasoningPayloadCompression(
        String symbol,
        String anchorTimeframe,
        Map<String, Object> leadingScenario,
        List<Map<String, Object>> alternates,
        List<Map<String, Object>> decisionZones,
        List<Map<String, Object>> triggerWatchList,
        List<String> contradictionSummary
) {}
```

---

# 7. Configuration

Package:
`com.dtech.elliott.advanced.scenario.filter.config`

## 7.1 FilterConfig

```java
public record FilterConfig(
        double hardPruneStructuralScoreFloor,
        double weakPruneStructuralScoreFloor,
        double ambiguityCeilingForLeadingScenario,
        double contradictionPenaltyWeight,
        double ambiguityPenaltyWeight,
        double structuralWeight,
        double confluenceWeight,
        double momentumWeight,
        double tradeUtilityWeight,
        double triggerReadinessWeight,
        double invalidationBucketSize,
        int maxMembersPerCluster,
        int maxFamiliesBeforeCompression,
        int maxActiveAlternates,
        int maxWeakAlternates,
        boolean keepContrarianAlternate,
        boolean suppressNoTradeNoiseFamilies
) {}
```

### Recommended defaults
- `hardPruneStructuralScoreFloor = 0.20`
- `weakPruneStructuralScoreFloor = 0.35`
- `invalidationBucketSize = configurable by symbol volatility`
- `maxActiveAlternates = 2`
- `maxWeakAlternates = 1`

---

# 8. Public API

Package:
`com.dtech.elliott.advanced.scenario.filter.api`

## 8.1 ScenarioFilterEngine

```java
package com.dtech.elliott.advanced.scenario.filter.api;

import java.util.List;
import com.dtech.elliott.advanced.domain.scenario.Scenario;
import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.FilteredScenarioSet;

public interface ScenarioFilterEngine {
    FilteredScenarioSet filter(List<Scenario> rawScenarios, FilterConfig config);
}
```

## 8.2 Default implementation
`DefaultScenarioFilterEngine`

Responsibilities:
- coordinate every second-pass component in order
- ensure deterministic sort order
- ensure null-safe handling
- ensure final compression rules are enforced

---

# 9. Component-by-Component Specification

# 9.1 Hard Pruning

Package:
`com.dtech.elliott.advanced.scenario.filter.prune`

## 9.1.1 HardPruner

```java
public interface HardPruner {
    List<Scenario> prune(List<Scenario> rawScenarios, FilterConfig config);
}
```

### Purpose
Remove obviously unusable scenarios before normalization.

### Prune conditions
Remove scenario if:
- status already `INVALIDATED`
- hard invalidation already triggered
- missing expected move
- no usable invalidation level can be derived
- structural legality score below `hardPruneStructuralScoreFloor`
- contradictory evidence exceeds allowed hard threshold
- no structures and no patterns linked
- empty explanation and empty reason codes and empty metrics

### Notes
- do not remove a contrarian but valid alternate just because it is minority
- only prune for hard invalidity or near-uselessness

### Required helper
`InvalidationStateChecker`

---

# 9.2 Scenario Normalization

Package:
`com.dtech.elliott.advanced.scenario.filter.normalize`

## 9.2.1 ScenarioNormalizer

```java
public interface ScenarioNormalizer {
    List<NormalizedScenario> normalize(List<Scenario> scenarios, FilterConfig config);
}
```

### Purpose
Create comparable, flattened scenario objects.

### Required logic
For each raw scenario:
1. derive directional bias
2. derive dominant structure family
3. derive normalized expected move type
4. pick one primary invalidation level
5. compute invalidation tolerance
6. flatten pattern names to pattern type strings
7. flatten structure types to structure family list
8. compute `decisionZoneNearby`
9. compute `triggerEligible`
10. deduplicate explanations
11. deduplicate reason codes
12. preserve source scenario id

### Important rule
If a raw scenario has multiple invalidation levels:
- choose the most immediate and structurally coherent one
- preserve others in metadata if needed
- do not average invalidations

### Helper components
- `DirectionalBiasResolver`
- `ExpectedMoveResolver`
- `PrimaryInvalidationResolver`
- `StructureFamilyResolver`
- `ScenarioMetadataFlattener`

---

# 9.3 Signature Building and Deduplication

Package:
`com.dtech.elliott.advanced.scenario.filter.dedupe`

## 9.3.1 SignatureBuilder

```java
public interface SignatureBuilder {
    ScenarioSignature build(NormalizedScenario scenario, FilterConfig config);
}
```

### Logic
Signature must be based on:
- symbol
- anchor timeframe
- directional bias
- expected move type
- dominant structure family
- invalidation bucket
- decision zone proximity
- trigger eligibility

### Invalidation bucketing
```java
roundedInvalidationBucket = Math.round(primaryInvalidationLevel / invalidationBucketSize)
```

---

## 9.3.2 ScenarioDeduplicator

```java
public interface ScenarioDeduplicator {
    List<ScenarioCluster> deduplicate(List<NormalizedScenario> scenarios, FilterConfig config);
}
```

### Logic
1. group by exact signature
2. inside each signature, merge near-identical members
3. cap members per cluster
4. aggregate score components
5. combine explanation lines
6. combine structures/patterns

### Merge rules
Two normalized scenarios may be merged if:
- same signature
- absolute invalidation difference within tolerance
- same directional bias
- same expected move type

### Required outputs
Each cluster must preserve:
- all source scenario ids
- combined evidence
- aggregated scores
- merged pattern and structure lists

---

# 9.4 Scenario Family Classification

Package:
`com.dtech.elliott.advanced.scenario.filter.classify`

## 9.4.1 ScenarioFamilyClassifier

```java
public interface ScenarioFamilyClassifier {
    List<ScenarioFamilyCandidate> classify(List<ScenarioCluster> clusters, FilterConfig config);
}
```

### Purpose
Convert clusters into interpretable market-state families.

### Classification rules

#### Rule family 1 — Compression
If cluster contains mostly:
- triangles
- wedges
- channels
and expected move is not yet directional,
classify as:
- `SIDEWAYS_COMPRESSION_BEFORE_BREAKOUT`

#### Rule family 2 — Bullish continuation after compression
If:
- directional bias is UP
- cluster contains falling wedge / ascending triangle / bullish correction completion
- expected move type suggests continuation up
classify as:
- `BULLISH_CONTINUATION_AFTER_COMPRESSION`

#### Rule family 3 — Bearish breakdown after compression
If:
- directional bias is DOWN
- cluster contains descending triangle / rising wedge / breakdown watch
classify as:
- `BEARISH_BREAKDOWN_AFTER_COMPRESSION`

#### Rule family 4 — Bullish continuation after correction
If:
- structure family is CORRECTIVE
- expected move type is continuation up
- confluence strong
classify as:
- `BULLISH_CONTINUATION_AFTER_CORRECTION`

#### Rule family 5 — Bearish reversal after terminal push
If:
- terminal patterns dominate
- rising wedge / H&S / double top / diagonal exhaustion present
- expected move is down
classify as:
- `BEARISH_REVERSAL_AFTER_TERMINAL_PUSH`

#### Rule family 6 — Ongoing corrective complexity
If:
- no clear directional resolution
- multiple corrective structures survive
classify as:
- `ONGOING_CORRECTIVE_COMPLEXITY`

#### Rule family 7 — Low conviction reversal seed
If:
- double tops / bottoms / early H&S / single-leg reversal seeds exist
- but no trigger and low maturity
classify as:
- `LOW_CONVICTION_REVERSAL_SEED`

#### Rule family 8 — No trade noise
If:
- ambiguity too high
- no direction
- no usable invalidation
classify as:
- `NO_TRADE_NOISE`

---

## 9.4.2 ClassificationHelper

Support methods:
- `containsCompressionPatterns`
- `containsTerminalPatterns`
- `containsBullishReversalSeeds`
- `containsBearishReversalSeeds`
- `hasDirectionalContinuationBias`
- `hasCorrectionCompletionContext`

---

# 9.5 Conflict Resolution

Package:
`com.dtech.elliott.advanced.scenario.filter.conflict`

## 9.5.1 ConflictResolver

```java
public interface ConflictResolver {
    ScenarioConflictSet resolve(List<ScenarioFamilyCandidate> families, FilterConfig config);
}
```

### Purpose
Separate bullish, bearish, and neutral families and expose their competition.

### Logic
1. partition families by directional bias
2. identify dominant conflict mode
3. attach explanations such as:
   - bullish continuation family exists but bearish terminal family remains active
   - compression family blocks directional confidence
4. do not prune here

### Output rules
- preserve all relevant families
- no ranking here, only conflict structure

---

# 9.6 Scoring

Package:
`com.dtech.elliott.advanced.scenario.filter.score`

## 9.6.1 FamilyScorer

```java
public interface FamilyScorer {
    List<ScenarioFamilyCandidate> score(List<ScenarioFamilyCandidate> families, FilterConfig config);
}
```

### Required score dimensions

#### Structural strength
Derived from:
- aggregated structural score
- source scenario validity
- consistency of source cluster members

#### Confluence strength
Derived from:
- confluence zones
- decision zone proximity
- invalidation tightness

#### Momentum alignment
Derived from:
- alignment with source momentum metrics
- absence of strong contradiction

#### Ambiguity penalty
Higher if:
- family internally contains too many conflicting members
- multiple family directions remain equally likely
- no clear expected move

#### Contradiction penalty
Higher if:
- lower-TF patterns strongly contradict family
- higher-TF family logic conflicts with raw source assumptions

#### Trade utility
Higher if:
- invalidation is near
- expected move is directional
- confluence and trigger context exist
- structure is not immature noise

#### Trigger readiness
Higher if:
- trigger eligible
- decision zone nearby
- price action trigger is plausible soon

### Final rank formula
```text
finalRankScore =
    structuralWeight * structuralStrength
  + confluenceWeight * confluenceStrength
  + momentumWeight * momentumAlignment
  + tradeUtilityWeight * tradeUtility
  + triggerReadinessWeight * triggerReadiness
  - ambiguityPenaltyWeight * ambiguityPenalty
  - contradictionPenaltyWeight * contradictionPenalty
```

### Important
All component scores must be preserved in `FamilyScore`.
Do not only return final score.

---

## 9.6.2 TradeUtilityScorer

Purpose:
Separate “interesting analysis” from “tradable soon”.

### Trade utility rules
Higher when:
- invalidation close
- decision zone nearby
- trigger eligible
- projected move directional
- family not marked low-conviction seed

Lower when:
- no clear invalidation
- broad chop implied
- no trigger zone
- family implies “wait” rather than “act”

---

# 9.7 Compression of Final Output

Package:
`com.dtech.elliott.advanced.scenario.filter.compress`

## 9.7.1 ScenarioCompressor

```java
public interface ScenarioCompressor {
    FilteredScenarioSet compress(
            List<ScenarioFamilyCandidate> scoredFamilies,
            ScenarioConflictSet conflictSet,
            FilterConfig config
    );
}
```

### Purpose
Reduce family list to final presentation set.

### Logic
1. sort by final rank descending
2. select top family as leading if ambiguity below leading ceiling
3. select up to `maxActiveAlternates`
4. select up to `maxWeakAlternates`
5. optionally keep one contrarian alternate if configured
6. move suppressed or invalidated families to separate bucket
7. optionally suppress `NO_TRADE_NOISE`

### Important rules
- there must never be more than one leading scenario
- if top two are too close and both contradictory, leading may still be compression/neutral
- weak alternates must remain visible for transparency

---

# 9.8 Human Summary Generation

Package:
`com.dtech.elliott.advanced.scenario.filter.summary`

## 9.8.1 HumanResearchSummaryBuilder

```java
public interface HumanResearchSummaryBuilder {
    HumanResearchSummary build(FilteredScenarioSet filteredScenarioSet);
}
```

### Summary rules
The human summary must say:
- current market state in plain language
- top scenario
- alternates
- what would confirm each
- what invalidates each
- what to do now: wait / watch breakout / watch rejection / early entry only on trigger

### Example output shape
- `marketStateSummary`: “Market remains in compression with bullish and bearish resolutions both alive.”
- `leadingScenarioSummary`: list of 3–5 bullets
- `actionHandlingNotes`: list of 3–5 bullets
- `invalidationNotes`: list of primary invalidation levels and what they mean

---

## 9.8.2 ReasoningPayloadCompressionBuilder

```java
public interface ReasoningPayloadCompressionBuilder {
    ReasoningPayloadCompression build(FilteredScenarioSet filteredScenarioSet);
}
```

### Rules
The AI payload must be:
- compact
- structured
- deterministic
- free of duplicate noise
- limited to leading + active alternates + major contradiction summary

---

# 10. Orchestration

Package:
`com.dtech.elliott.advanced.scenario.filter.orchestration`

## 10.1 DefaultScenarioFilterEngine

```java
public class DefaultScenarioFilterEngine implements ScenarioFilterEngine {
    // inject all subcomponents
}
```

### Execution order
1. hard prune raw scenarios
2. normalize
3. deduplicate
4. classify families
5. resolve conflicts
6. score families
7. compress final set
8. build human summary
9. build reasoning payload

### Determinism requirements
- sort collections before grouping where order matters
- do not rely on hash iteration order
- use stable comparators
- use explicit tie-breakers

---

# 11. Tie-Breaking Rules

When two families have same final rank:
1. higher structural strength wins
2. if still tied, higher confluence strength wins
3. if still tied, lower ambiguity penalty wins
4. if still tied, higher trade utility wins
5. if still tied, lexicographic ID order

This must be enforced to preserve stable outputs.

---

# 12. Error Handling

Rules:
- never silently drop scenario because of malformed optional metadata
- malformed scenario should be logged and skipped only if unusable
- normalization should tolerate missing pattern list or missing optional scores
- if all scenarios are pruned, return valid empty-state `FilteredScenarioSet` with clear summary

Add `FilteringException` only for true system failures, not bad scenario data.

---

# 13. Logging and Observability

Add structured logs for:
- raw scenario count
- hard-pruned count
- normalized count
- cluster count
- family count before and after compression
- leading scenario selection
- suppressed contrarian alternate
- no-trade/noise suppression

Suggested event names:
- `scenario.filter.prune`
- `scenario.filter.normalize`
- `scenario.filter.cluster`
- `scenario.filter.classify`
- `scenario.filter.score`
- `scenario.filter.compress`

---

# 14. Test Strategy

Use JUnit 5.

All public classes above must be tested.

---

## 14.1 Unit Tests

### HardPrunerTest
Cases:
- removes invalidated scenarios
- removes missing-invalidation scenarios
- keeps contrarian but valid scenario

### ScenarioNormalizerTest
Cases:
- resolves direction correctly
- selects primary invalidation correctly
- deduplicates explanation strings
- computes decision zone flag

### SignatureBuilderTest
Cases:
- identical scenarios produce same signature
- invalidation rounding stable
- different direction produces different signature

### ScenarioDeduplicatorTest
Cases:
- same signature grouped
- near-identical invalidations merged
- merged cluster preserves multiple source ids

### ScenarioFamilyClassifierTest
Cases:
- triangle + wedge → compression family
- falling wedge + bullish move → bullish continuation after compression
- rising wedge + bearish move → bearish breakdown after compression
- double bottom seed only → low-conviction reversal seed

### ConflictResolverTest
Cases:
- bullish + bearish + neutral families partitioned correctly
- dominant conflict mode resolved correctly

### FamilyScorerTest
Cases:
- high confluence and low ambiguity rank higher
- contradiction penalty reduces score
- trigger readiness improves trade utility

### ScenarioCompressorTest
Cases:
- exactly one leading scenario
- alternates capped correctly
- weak alternates preserved
- no-trade noise suppressed when configured

### HumanResearchSummaryBuilderTest
Cases:
- summary includes market state
- summary includes leading scenario
- summary includes invalidation notes

---

## 14.2 Integration Tests

### Integration Case 1
Raw scenarios:
- repeated triangles
- repeated wedges
- similar invalidation levels
Expected:
- one compression family
- one bullish alternate
- one bearish alternate

### Integration Case 2
Raw scenarios:
- bullish correction completion
- inverse H&S
- strong confluence
Expected:
- bullish continuation after correction becomes leading

### Integration Case 3
Raw scenarios:
- low-confidence double bottoms only
Expected:
- low-conviction reversal seed
- no leading tradable scenario

### Integration Case 4
Raw scenarios:
- terminal rising wedge
- bearish H&S
- strong downside move expected
Expected:
- bearish reversal after terminal push

---

## 14.3 Golden Tests

Store raw stage-1 scenario JSON and expected filtered JSON.

Recommended fixture folders:
```text
src/test/resources/fixtures/scenario-filter/
  compression_case/
  bullish_continuation_case/
  bearish_terminal_case/
  noise_case/
```

Each case should contain:
- `raw-scenarios.json`
- `filter-config.json`
- `expected-filtered-output.json`

---

# 15. Sample Mapping Rules From Your Current Output Style

These rules should be codified explicitly.

### Rule A
If raw scenarios contain repeated:
- `SYMMETRICAL_TRIANGLE`
- `ASCENDING_TRIANGLE`
- `DESCENDING_TRIANGLE`
- `WEDGE`
- `CHANNEL`
and all imply waiting for more pivots / breakout / retest,
then classify dominant family as:
- `SIDEWAYS_COMPRESSION_BEFORE_BREAKOUT`

### Rule B
If repeated bullish `FALLING_WEDGE`, `ASCENDING_TRIANGLE`, or correction-complete logic exists,
then create alternate:
- `BULLISH_CONTINUATION_AFTER_COMPRESSION`

### Rule C
If repeated bearish `RISING_WEDGE`, `DESCENDING_TRIANGLE`, terminal signals or reversal patterns exist,
then create alternate:
- `BEARISH_BREAKDOWN_AFTER_COMPRESSION`
or
- `BEARISH_REVERSAL_AFTER_TERMINAL_PUSH`

### Rule D
If only first-leg double top/double bottom seeds exist,
do not expose as primary scenario family.
Map to:
- `LOW_CONVICTION_REVERSAL_SEED`

---

# 16. Build Order for Qwen 7B

To improve code quality, ask the model to generate in this exact order:

1. enums and config
2. domain records
3. public interfaces
4. normalizer + tests
5. signature builder + tests
6. deduplicator + tests
7. classifier + tests
8. conflict resolver + tests
9. scorer + tests
10. compressor + tests
11. summary builders + tests
12. orchestration class + integration tests

Do not ask it to generate everything in one shot.

---

# 17. Acceptance Criteria

This module is acceptable only if it can:

1. reduce raw scenario noise to a small family set
2. merge duplicate raw scenarios deterministically
3. preserve a leading scenario and valid alternates
4. classify compression, continuation, terminal, correction, and low-conviction seed states
5. rank scenarios with explicit score components
6. preserve invalidation and trigger context
7. emit a human-readable research summary
8. emit a compact AI reasoning payload
9. pass unit and integration tests
10. remain stable for the same input across repeated runs

---

# 18. Final Summary

This second-pass engine is the core of usability.

Without it, your system is only a raw extractor.

With it, the system becomes:
- a research engine for your group
- a clean scenario reducer
- a bridge between pattern noise and trading logic
- a stable input layer for the later AI reasoning engine

The package root for this implementation remains:

`com.dtech.elliott.advanced`
