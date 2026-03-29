# Elliott Wave Research and Trade-Preparation Engine
## Low-Level Design Specification (LLD)

Version: 1.0  
Audience: code-generation model, developers, test authors  
Goal: deterministic extraction of market possibilities, structured evaluation, and handoff to reasoning/trade-selection layers.

---

## 1. Purpose

This system is designed to do two different jobs cleanly:

1. **Part 1: Research engine**
   - enumerate all meaningful Elliott Wave possibilities
   - correlate them with classical chart patterns
   - detect confluence and decision zones
   - evaluate and prune invalid or weak branches
   - expose surviving scenario families with traceable logic

2. **Part 2: Action-preparation engine**
   - detect lower-timeframe triggers
   - convert research scenarios into trade-ready setup candidates
   - hand structured outputs to an AI reasoning engine for final trade judgement

This document focuses on the full low-level design of the deterministic system that should be built before the final AI reasoning pass.

The system must not pretend to know one “true count” in real time. Its job is to extract, organize, score, invalidate, and evolve all materially relevant possibilities.

---

## 2. Non-Goals

This system is **not** responsible for:
- brokerage execution
- final order placement
- portfolio sizing
- PnL tracking
- news/sentiment analysis
- pretending to output a single always-correct Elliott count

Those may be added later, but they are not part of this build.

---

## 3. High-Level Architecture

```text
OHLC + Indicator Inputs
    ↓
[1] Market Decomposition Engine
    ↓
[2] Elliott Structure Extraction Engine
    ↓
[3] Classical Pattern Detection Engine
    ↓
[4] Confluence + Decision Zone Engine
    ↓
[5] Scenario Assembly Engine
    ↓
[6] Scenario Evaluation + Pruning Engine
    ↓
[7] Trigger + Entry Refinement Engine
    ↓
[8] Hypothesis Manager
    ↓
[9] Reasoning Engine Interface
```

### Design principle
Each stage must:
- consume structured inputs only
- produce structured outputs only
- be independently testable
- avoid hidden side effects
- preserve explanation data for downstream transparency

---

## 4. Core Product Logic

### 4.1 What the system must answer
At any point in time, for each timeframe:
- what wave structures are legally possible?
- which partial structures are in formation?
- which chart patterns are visible or emerging?
- which lower-TF structures support or contradict higher-TF branches?
- where are decision zones?
- what invalidates each scenario?
- what next move does each scenario imply?
- if someone wants to trade this instrument, where would early low-risk entries even become possible?

### 4.2 What the system must not do too early
It must not:
- collapse to one scenario too early
- allow the AI layer to reconstruct raw structure from scratch
- hide invalidation logic
- emit vague narrative without machine-readable state

---

## 5. Data Model Overview

All modules must use typed objects. Suggested implementation can be Python dataclasses or Pydantic models.

### 5.1 Candle
```python
class Candle:
    symbol: str
    timeframe: str
    open_time: int
    close_time: int
    open: float
    high: float
    low: float
    close: float
    volume: float
```

### 5.2 Pivot
```python
class Pivot:
    id: str
    symbol: str
    timeframe: str
    index: int
    time: int
    price: float
    pivot_type: str  # HIGH / LOW
    strength: float
    left_span: int
    right_span: int
    source: str      # zigzag / local-extrema / hybrid
```

### 5.3 Swing
```python
class Swing:
    id: str
    symbol: str
    timeframe: str
    start_pivot_id: str
    end_pivot_id: str
    direction: str   # UP / DOWN
    price_change: float
    percent_change: float
    bar_count: int
    slope: float
    volatility_score: float
    momentum_profile_id: str | None
```

### 5.4 MomentumProfile
```python
class MomentumProfile:
    id: str
    symbol: str
    timeframe: str
    start_index: int
    end_index: int
    macd_value: float | None
    macd_hist_peak: float | None
    macd_slope: float | None
    rsi_min: float | None
    rsi_max: float | None
    rsi_close: float | None
    adx_close: float | None
    plus_di: float | None
    minus_di: float | None
    bb_width_start: float | None
    bb_width_end: float | None
```

### 5.5 StructureCandidate
```python
class StructureCandidate:
    id: str
    symbol: str
    timeframe: str
    degree: str
    structure_type: str
    family: str            # MOTIVE / CORRECTIVE / COMPRESSION / TERMINAL
    state: str             # pattern-specific partial/completed state
    pivot_ids: list[str]
    leg_labels: list[str]
    current_position: str
    completion_ratio: float
    structural_validity: str   # VALID / INVALID / WEAK_VALID
    allowed_wave_roles: list[str]
    parent_compatibility_keys: list[str]
    child_expectation_keys: list[str]
    metrics: dict
    invalidation_rules: list[dict]
    confirmation_rules: list[dict]
    explanation: list[str]
```

### 5.6 PatternCandidate
```python
class PatternCandidate:
    id: str
    symbol: str
    timeframe: str
    pattern_type: str
    state: str
    direction: str
    pivot_ids: list[str]
    key_levels: dict
    quality_score: float
    confirmation_rules: list[dict]
    invalidation_rules: list[dict]
    explanation: list[str]
```

### 5.7 ConfluenceZone
```python
class ConfluenceZone:
    id: str
    symbol: str
    timeframe: str
    lower_price: float
    upper_price: float
    zone_type: str         # FIB / SR / CHANNEL / NECKLINE / MULTI
    contributing_factors: list[dict]
    score: float
    linked_structure_ids: list[str]
    linked_pattern_ids: list[str]
```

### 5.8 Scenario
```python
class Scenario:
    id: str
    symbol: str
    anchor_timeframe: str
    degree: str
    scenario_family: str
    structure_ids: list[str]
    pattern_ids: list[str]
    confluence_zone_ids: list[str]
    current_market_state: str
    expected_next_moves: list[dict]
    hard_invalidations: list[dict]
    soft_invalidations: list[dict]
    triggers_of_interest: list[dict]
    status: str
    scores: dict
    explanation: list[str]
```

### 5.9 EntryCandidate
```python
class EntryCandidate:
    id: str
    symbol: str
    scenario_id: str
    direction: str
    entry_style: str       # AGGRESSIVE / MODERATE / CONSERVATIVE
    trigger_type: str
    entry_zone: dict
    stop_logic: dict
    target_logic: dict
    expected_move_type: str
    confidence_components: dict
    explanation: list[str]
```

---

## 6. Module-by-Module Low-Level Design

# 6.1 Market Decomposition Engine

## Purpose
Convert raw candles into reusable structural primitives.

## Why this exists
Everything downstream depends on pivots and swings. If pivot extraction is poor, every other engine becomes noisy.

## Responsibilities
- compute pivots at multiple sensitivities
- build swings between pivots
- attach momentum and volatility profiles to swings
- preserve multiple degrees of decomposition

## Subcomponents

### 6.1.1 PivotDetector
#### Inputs
- candles for one symbol and timeframe
- pivot configuration

#### Logic
Use a hybrid approach:
1. local extrema detection
2. ZigZag-like filtering using percent or ATR threshold
3. optional minimum bar distance between pivots
4. strength scoring based on:
   - reversal depth after pivot
   - bars held before invalidation
   - local volume / volatility context

#### Outputs
List of `Pivot`

#### Testability
- synthetic up/down series
- equal-high/equal-low handling
- noisy sideways market
- threshold boundary cases

### 6.1.2 MultiDegreePivotBuilder
#### Purpose
Produce pivot sets for different structural degrees.

#### Logic
Generate pivot layers:
- fine
- medium
- coarse

By either:
- multiple ZigZag thresholds
- ATR-scaled filters
- hierarchical pruning of minor pivots

#### Why needed
The same structure may exist at different degrees. This module avoids degree blindness.

### 6.1.3 SwingBuilder
#### Logic
Create swings from consecutive pivots and compute:
- direction
- percentage change
- bar count
- slope
- volatility
- relative size rank within local window

#### Testability
- correct direction
- bar count correctness
- slope sign and magnitude
- no missing links

### 6.1.4 IndicatorProfileService
#### Inputs
candles, swing boundaries

#### Outputs
MomentumProfile for each swing and rolling context windows.

#### Logic
Precompute and cache:
- RSI
- MACD / histogram / slope
- ADX / +DI / -DI
- Bollinger width
- ATR

#### Why needed
These are used later for structure scoring, not for raw structure generation.

---

# 6.2 Elliott Structure Extraction Engine

## Purpose
Generate all materially valid Elliott structure candidates from pivot sequences.

## Why this exists
This is the core extractor. It should be strict enough to reject illegal counts but flexible enough to preserve alternates.

## Design approach
Implement a grammar-driven candidate generator.

## Key rule
**Use pivots for generation; use indicators for scoring.**

## Subcomponents

### 6.2.1 StructureDefinitionRegistry
A central registry of all supported structure grammars.

Each grammar defines:
- required leg count
- required internal directional pattern
- overlap rules
- retracement / extension ranges
- momentum expectations
- allowed wave roles
- allowed parent structures
- allowed partial states

#### Supported initial grammars
- Impulse
- LeadingDiagonal
- EndingDiagonal
- Zigzag
- FlatRegular
- FlatExpanded
- FlatRunning
- TriangleContracting
- TriangleBarrier
- CombinationWXY

### 6.2.2 PartialStateModel
#### Purpose
Support incomplete structure detection.

Example:
- Impulse: `W1`, `W1_W2`, `W1_W2_W3`, `W1_W2_W3_W4`, `W1_W2_W3_W4_W5`, `COMPLETE`
- Triangle: `A`, `AB`, `ABC`, `ABCD`, `ABCDE`, `COMPLETE`, `POST_BREAK`

#### Why needed
Real-time systems trade incomplete structures, not textbook finished ones.

### 6.2.3 CandidateWindowEnumerator
#### Purpose
Generate pivot windows for grammar fitting.

#### Logic
For each pivot layer and timeframe:
- enumerate trailing windows of length 3 to 13 pivots
- include rolling window variants
- include last-N significant pivots
- apply direction-aware pruning

#### Performance note
Do not brute force every possible window in large histories. Limit to recent regime windows and strongest pivots.

### 6.2.4 GrammarMatcher
#### Inputs
pivot window, structure definition

#### Logic
Check:
- directional sequence
- leg count
- pivot alternation
- overlap legality
- retracement ranges
- extension ranges
- internal consistency

#### Outputs
`StructureCandidate` or rejection reason

#### Important
Return both:
- pass/fail
- reason codes for fail

This is important for debugging and tests.

### 6.2.5 WaveRoleMapper
#### Purpose
Given a valid structure candidate, identify legal wave roles.

Example:
- impulse can be 1, 3, 5, A, C
- triangle can be 4, B
- ending diagonal can be 5, C

#### Output
`allowed_wave_roles`

### 6.2.6 ParentChildCompatibilityService
#### Purpose
Attach possible parent and child structure relationships.

Example:
- lower-TF zigzag may support higher-TF wave 2, B, X, or triangle leg
- ending diagonal supports terminal context, not early impulse continuation

#### Why needed
A local structure without parent/child context is weak.

### 6.2.7 StructureScoringSupport
Precompute soft metrics:
- fib fit
- time proportionality
- alternation quality
- momentum consistency
- channel quality
- terminal divergence quality

Do not decide final scenario here. Only attach metrics.

---

# 6.3 Classical Pattern Detection Engine

## Purpose
Find classical chart patterns and their formation stage.

## Why this exists
Patterns are both:
- structural evidence for Elliott branches
- execution context for lower timeframe trading

## Patterns to support in v1
- HeadAndShoulders
- InverseHeadAndShoulders
- DoubleTop
- DoubleBottom
- RisingWedge
- FallingWedge
- AscendingTriangle
- DescendingTriangle
- SymmetricalTriangle
- Flag
- Channel
- CupAndHandle

## Pattern states
- NOT_PRESENT
- EARLY_CANDIDATE
- PARTIAL
- NEAR_CONFIRMATION
- CONFIRMED
- FAILED
- RETEST
- POST_BREAK

## Subcomponents

### 6.3.1 PatternWindowSelector
Select candidate windows from pivots/swings.

### 6.3.2 PatternMatchers
One matcher per pattern type.

#### Example: HeadAndShouldersMatcher
Checks:
- left shoulder local peak
- head higher than shoulders
- shoulder symmetry tolerance
- neckline computability
- right shoulder state
- neckline angle tolerance
- breakdown confirmation

#### Important
Must support partial pattern logic:
- LS complete
- head complete
- RS pending
- RS formed not confirmed
- neckline broken
- neckline retest

### 6.3.3 PatternQualityScorer
Compute:
- symmetry
- clean pivot separation
- neckline clarity
- shoulder proportion
- breakout quality
- volume or momentum consistency when available

### 6.3.4 Pattern-Elliott Correlator
Map patterns to compatible Elliott contexts.

Examples:
- inverse H&S supports wave 2 / B ending or new impulse start
- H&S supports terminal 5, B top, or reversal after completion
- wedge supports diagonal or terminal exhaustion
- handle supports wave 4-like continuation

---

# 6.4 Confluence + Decision Zone Engine

## Purpose
Find price zones where multiple structural reasons cluster.

## Why this exists
Trades should not be based on pattern labels alone. Good early entries come from location plus trigger.

## Zone types
- Fibonacci retracement zone
- Fibonacci extension target zone
- Horizontal support/resistance zone
- Channel boundary zone
- Neckline zone
- Prior pivot memory zone
- Multi-factor decision zone

## Subcomponents

### 6.4.1 FibLevelBuilder
Build:
- retracement levels: 23.6, 38.2, 50, 61.8, 78.6
- extension levels: 100, 127.2, 161.8, etc.

Need both absolute levels and tolerance zones.

### 6.4.2 HorizontalSRBuilder
Compute significant horizontal levels from:
- major pivots
- repeated reaction clusters
- gap or congestion memory if available later

### 6.4.3 DynamicBoundaryBuilder
Create:
- channel boundaries
- wedge boundaries
- trendline envelopes
- pattern necklines

### 6.4.4 ConfluenceAggregator
Merge overlapping levels into zones.

#### Scoring inputs
- count of factors
- factor diversity
- higher timeframe importance
- role relevance to top scenarios
- historical reaction count

### 6.4.5 DecisionZoneClassifier
A zone becomes a decision zone when:
- multiple scenarios converge there
- invalidation is nearby
- post-reaction move would likely be directional
- pattern or Elliott completion may occur there

#### Output
Tag zones such as:
- corrective_completion_zone
- terminal_reversal_zone
- breakout_decision_zone
- continuation_retest_zone

---

# 6.5 Scenario Assembly Engine

## Purpose
Convert many disconnected structures into coherent scenario objects.

## Why this exists
Users and downstream reasoning should consume scenario families, not raw disconnected pattern matches.

## Responsibilities
- group compatible structure candidates
- attach supporting patterns
- attach contradicting patterns
- attach confluence zones
- define expected next move classes
- create scenario families

## Scenario families
Examples:
- bullish_continuation_after_correction
- bearish_reversal_after_terminal_push
- sideways_compression_before_breakout
- ongoing_corrective_complexity
- motive_extension_still_active

## Subcomponents

### 6.5.1 ScenarioGrouper
Group candidate structures that imply the same market state.

### 6.5.2 ConflictResolver
Track internal contradictions:
- higher TF bullish, lower TF bearish H&S
- structure supports terminal reversal but momentum supports strong continuation

Do not delete automatically. Store conflict scores.

### 6.5.3 NextMoveProjector
For each scenario, define what is expected next:
- continuation up
- continuation down
- one more corrective leg
- terminal reversal
- compression breakout
- no-trade chop

### 6.5.4 InvalidationAssembler
Combine:
- hard structure invalidations
- pattern invalidations
- zone invalidations
- trigger invalidations

---

# 6.6 Scenario Evaluation + Pruning Engine

## Purpose
Score all scenarios and assign survival status.

## Why this exists
Enumeration without pruning becomes unusable.

## Status buckets
- LEADING
- ACTIVE_ALTERNATE
- WEAK_ALTERNATE
- AWAITING_TRIGGER
- INVALIDATED
- COMPLETED

## Scoring dimensions
Use separate component scores rather than one opaque score.

### 6.6.1 Structural legality score
Highest importance.
- binary invalidation or near-binary penalties

### 6.6.2 Elliott quality score
- alternation
- fib conformity
- internal completeness
- time proportionality
- terminal behavior

### 6.6.3 Pattern support score
- lower-TF support
- contradiction penalty
- pattern maturity

### 6.6.4 Momentum support score
- wave 3 strength
- wave 5 divergence
- triangle decay
- correction weakness
- breakout expansion signs

### 6.6.5 Confluence score
- relevance of current location
- decision-zone strength
- invalidation tightness

### 6.6.6 Relabeling penalty
Penalize scenarios that survive only by constant relabeling over time.

### 6.6.7 Ambiguity penalty
Penalize scenarios that remain too unresolved relative to alternatives.

## Pruning rules
- drop all hard-invalidated scenarios
- keep top N per scenario family
- keep a small number of contrarian alternates above floor score
- merge duplicates with same action implications

---

# 6.7 Trigger + Entry Refinement Engine

## Purpose
Detect lower-timeframe action signals that convert scenario research into actionable setup candidates.

## Why this exists
The best entries come from:
- structure context
- confluence location
- actual trigger candle or micro-break

Not from Elliott labels alone.

## Trigger types
- hammer
- shooting star
- bullish engulfing
- bearish engulfing
- long rejection wick
- micro trendline break
- neckline break
- breakout candle
- failed-break reclaim
- retest hold

## Entry styles

### Aggressive
Enter at zone reaction.
Example:
- hammer at 38.2 retracement inside right shoulder zone
- stop beyond local invalidation

### Moderate
Enter on micro-structure break after zone reaction.

### Conservative
Enter after full pattern confirmation.
Example:
- neckline break or breakout retest

## Subcomponents

### 6.7.1 CandleTriggerDetector
Precise candle rules.
Do not use naive “hammer means bullish” logic.
Need:
- body-to-range ratio
- wick dominance
- close location within candle
- relative location to zone
- context direction

### 6.7.2 MicroStructureTriggerDetector
Find:
- break of last micro swing high/low
- break of correction trendline
- reclaim after false breakdown
- local pivot violation

### 6.7.3 TriggerContextValidator
Only allow triggers if:
- they occur in scenario-relevant zones
- scenario maturity supports action
- invalidation is sufficiently close
- no major higher-TF contradiction blocks trade quality

### 6.7.4 EntryBuilder
Create `EntryCandidate` objects with:
- entry style
- stop logic
- target logic
- rationale

#### Example stop logic
- aggressive right-shoulder short: SL above RS invalidation, not neckline
- breakout trade: SL around neckline retest or breakout failure point
- hammer-at-38.2 long: SL below rejection low / structure invalidation zone

---

# 6.8 Hypothesis Manager

## Purpose
Track scenario state across new candles.

## Why this exists
Scenarios are dynamic. Without a state manager, the system becomes stateless and noisy.

## Responsibilities
- persist active scenarios
- compare prior vs current scenario trees
- update scores
- mark invalidated branches
- promote rising alternates
- store trigger events
- record relabel count
- maintain scenario history for transparency

## State transitions
- AWAITING_TRIGGER → ACTIVE_ALTERNATE
- ACTIVE_ALTERNATE → LEADING
- LEADING → WEAK_ALTERNATE
- ANY → INVALIDATED
- ACTIVE_ALTERNATE → COMPLETED

## Additional metrics
- survival duration
- relabel count
- trigger count
- false trigger count
- scenario drift

---

# 6.9 Reasoning Engine Interface

## Purpose
Provide the AI layer with compact, structured, high-signal data.

## Why this exists
The AI engine should reason over curated scenario objects, not raw OHLC dumps.

## Input contract
The reasoning engine should receive:
- top scenario families
- active alternates
- conflict summary
- decision zones
- entry candidates
- invalidation map
- higher timeframe bias summary
- lower timeframe trigger summary

## Output contract
The reasoning engine may return:
- preferred bias
- preferred scenario family
- risk summary
- best actionable setups
- what to wait for
- what invalidates current thesis

---

## 7. Cross-Module Integration Details

### 7.1 Integration sequence
1. Decomposition must run before all others.
2. Structure and pattern engines may run in parallel once pivots/swings exist.
3. Confluence engine depends on structures, patterns, and swings.
4. Scenario assembly depends on structure candidates, pattern candidates, and zones.
5. Evaluation depends on scenario assembly plus momentum and contradiction data.
6. Trigger engine depends on active scenarios + decision zones + latest candles.
7. Hypothesis manager persists scenario evolution.
8. Reasoning engine consumes only finalized structured outputs.

### 7.2 Event model
Every engine should emit events such as:
- `structure_candidate_created`
- `pattern_candidate_promoted`
- `scenario_invalidated`
- `decision_zone_entered`
- `trigger_fired`
- `entry_candidate_created`

This improves observability and debugging.

### 7.3 Data persistence
Store at least:
- pivots
- swings
- scenario snapshots
- active scenario statuses
- trigger history
- entry history
- evaluation scores

### 7.4 Idempotency
Each run on the same candle set must produce the same deterministic outputs prior to the AI reasoning stage.

---

## 8. Suggested Package Structure

```text
engine/
  domain/
    models.py
    enums.py
    rules.py

  decomposition/
    pivot_detector.py
    pivot_hierarchy.py
    swing_builder.py
    indicator_profile_service.py

  elliott/
    structure_registry.py
    grammar_matcher.py
    partial_state_model.py
    wave_role_mapper.py
    parent_child_compatibility.py
    structure_metrics.py

  patterns/
    pattern_window_selector.py
    hns_matcher.py
    double_top_bottom_matcher.py
    wedge_matcher.py
    triangle_matcher.py
    flag_channel_matcher.py
    cup_handle_matcher.py
    pattern_quality.py
    pattern_elliott_correlation.py

  confluence/
    fib_builder.py
    horizontal_sr.py
    dynamic_boundaries.py
    confluence_aggregator.py
    decision_zone_classifier.py

  scenarios/
    scenario_grouper.py
    conflict_resolver.py
    next_move_projector.py
    invalidation_assembler.py
    scenario_evaluator.py
    scenario_pruner.py

  triggers/
    candle_trigger_detector.py
    micro_structure_trigger_detector.py
    trigger_context_validator.py
    entry_builder.py

  hypothesis/
    hypothesis_manager.py
    scenario_state_machine.py

  interfaces/
    reasoning_payload_builder.py

  tests/
    unit/
    integration/
    fixtures/
    golden/
```

---

## 9. Detailed Test Strategy

# 9.1 Unit tests

## Decomposition
- pivot extraction on monotonic series
- pivot extraction on noisy series
- degree hierarchy correctness
- swing metric correctness

## Elliott engine
- valid impulse accepted
- wave 2 beyond wave 1 start rejected
- wave 4 overlap in normal impulse rejected or downgraded
- triangle ABCDE partial states recognized
- zigzag recognized as 5-3-5
- wave role mapping correctness

## Pattern engine
- H&S partial state progression
- neckline calculation
- double top tolerance handling
- wedge convergence detection
- pattern invalidation detection

## Confluence engine
- fib zone creation
- overlapping levels merged
- zone score rises with factor diversity
- decision zone classification correctness

## Scenario engine
- compatible structures grouped
- contradictions recorded
- invalidations assembled
- duplicate scenarios merged

## Trigger engine
- hammer recognition with strict rules
- engulfing detection
- micro-break detection
- trigger blocked if not inside valid zone
- entry candidate SL logic correctness

## Hypothesis manager
- state transitions
- invalidation persistence
- relabel penalty increment
- scenario promotion logic

# 9.2 Integration tests
Use known synthetic and historical sequences.

### Integration test examples
1. Weekly impulse, daily wave-2 correction, 1H inverse H&S near 38.2 retracement
   - expect bullish continuation scenario
   - expect decision zone
   - expect aggressive entry candidate only after hammer

2. Weekly terminal wave-5 candidate, 4H H&S, 1H neckline break
   - expect bearish reversal family
   - expect terminal scenario + H&S support correlation

3. Sideways compression with triangle + low momentum
   - expect compression scenario
   - avoid premature continuation signal

# 9.3 Golden tests
Create a fixture library of manually labeled historical examples:
- clean impulse
- ending diagonal
- wave 2 zigzag
- wave 4 triangle
- double top
- H&S
- wedge reversal
- false breakout reclaim

Save expected outputs as JSON and compare.

# 9.4 Property-based tests
Useful for:
- pivot ordering
- swing continuity
- invalidation consistency
- no scenario can be both LEADING and INVALIDATED

# 9.5 Backtest-oriented validation
This is not final strategy backtesting, but structural timing validation:
- when did scenario first appear?
- when did it become leading?
- when did trigger appear?
- was invalidation respected?

---

## 10. Performance and Search Control

## Problem
Scenario enumeration can explode combinatorially.

## Controls
- beam search per timeframe and family
- max candidates per grammar per window
- max live scenarios per family
- deduplicate equivalent scenarios
- only keep top K + a few contrarians
- cache decomposition outputs
- cache structure metrics per pivot window

## Suggested defaults
- top 5 per family
- plus top 2 contrarian alternates
- per timeframe max 20 live scenarios before merge

---

## 11. Configuration

```python
class EngineConfig:
    symbol: str
    timeframes: list[str]

    pivot_thresholds: dict[str, float]
    pivot_min_distance: dict[str, int]

    fib_tolerance_pct: float
    pattern_tolerance_pct: float
    neckline_angle_tolerance: float
    shoulder_symmetry_tolerance: float

    max_candidate_windows: int
    max_structure_candidates_per_tf: int
    max_scenarios_per_family: int

    decision_zone_merge_ticks: float
    trigger_zone_tolerance_pct: float

    scoring_weights: dict[str, float]
    ambiguity_penalty_weight: float
    relabel_penalty_weight: float

    aggressive_entry_enabled: bool
    moderate_entry_enabled: bool
    conservative_entry_enabled: bool
```

---

## 12. Explanation and Transparency Requirements

Every final scenario and entry candidate must preserve explanation strings and machine-readable rule references.

Example:
- `reason_codes = ["TRIANGLE_OVERLAP_OK", "MOMENTUM_DECAY_PRESENT", "E_LEG_PENDING"]`
- `human_explanation = ["Repeated overlaps and contracting range support a triangle interpretation."]`

This is required so the group can inspect why a scenario exists.

---

## 13. Build Order / Milestones

## Milestone 1
Domain models, decomposition engine, fixture system

## Milestone 2
Elliott grammar engine with:
- impulse
- zigzag
- triangle
- ending diagonal

## Milestone 3
Pattern engine with:
- H&S
- inverse H&S
- double top/bottom
- wedge
- triangle

## Milestone 4
Confluence engine and decision zones

## Milestone 5
Scenario assembly + evaluation + pruning

## Milestone 6
Trigger engine + entry candidates

## Milestone 7
Hypothesis manager

## Milestone 8
Reasoning payload builder and end-to-end integration tests

---

## 14. Acceptance Criteria

The system is acceptable when it can:

1. generate multiple valid Elliott scenarios for the same market state
2. preserve partial structures, not only completed ones
3. correlate lower-TF chart patterns with higher-TF Elliott branches
4. produce confluence and decision zones
5. generate entry candidates only when trigger + location + context align
6. invalidate and reprioritize scenarios as new candles arrive
7. output machine-readable scenario and entry payloads for AI reasoning
8. provide transparent explanation and test coverage for each stage

---

## 15. Final Summary

This design is intentionally layered.

It separates:
- extraction from reasoning
- structure from triggers
- possibilities from trade suggestions

The deterministic engine must do the heavy structural work:
- find all meaningful possibilities
- score them
- cluster them
- invalidate weak ones
- detect decision zones
- emit trigger-aware entry candidates

Then the AI reasoning layer can operate on a small, high-quality, explainable set of scenario objects instead of raw market noise.
