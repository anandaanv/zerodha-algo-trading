# Elliott Wave Implementation Comparison

## Executive Summary

Three Elliott Wave implementations were analysed:
1. **alessioricco/ElliottWaves** – Python research notebook (GitHub)
2. **Wkemery/ElliotWaveAnalysis** – Python research notebook (GitHub)
3. **Our implementation** – Production Java engine (`AdvancedElliottService` + `ElliottWaveAnalyzer`)

The two external repos are near-identical academic/research prototypes. Our engine is substantially more sophisticated and production-ready.

---

## External Repos: Common Architecture

Both Python repos share the same approach:

### Pivot Detection
- `scipy.signal.argrelextrema` with a fixed window (e.g., order=5) to find local highs/lows
- No adaptive threshold — window size is a hard-coded parameter
- No ZigZag-style filtering for noise reduction

### Wave Search Strategy
- **Exhaustive O(n^9) combinatorial brute-force** — 9 nested loops over all pivot combinations
- Tries every combination of 9 pivot indices, validates structural rules
- Extremely slow on large datasets; impractical without pre-filtering

### Structural Validation Rules (13–20 rules)
- Wave 3 is not the shortest impulse wave
- Wave 2 does not retrace past the start of Wave 1
- Wave 4 does not overlap Wave 1 price territory (Dow rule)
- Wave alternation: W2 and W4 behave differently (shallow/deep)
- Some rules commented out (Wkemery repo has more inactive rules)

### Fitness / Scoring
- **alessioricco**: linear regression error minimisation — finds the wave fit closest to a straight-line slope
- **Wkemery**: Euclidean distance metric — selects the geometrically "cleanest" count

### Missing Features
- ❌ No Fibonacci ratio validation (W2=0.618, W3=1.618, W4=0.382)
- ❌ No multi-timeframe analysis
- ❌ No corrective wave classification (zigzag, flat, triangle, expanded flat)
- ❌ No wave degree hierarchy / fractal structure
- ❌ No gap detection, trendline detection, or confluence zones
- ❌ No real-time / production integration; pure offline notebooks
- ❌ No hypothesis tracking across scans

---

## Our Implementation: Architecture

Entry point: `AdvancedElliottService.analyze()` → calls `ElliottWaveAnalyzer.analyze()` + 6 additional pipeline steps.

### Pivot Detection
- **ZigZag-based** (`ZigZagService`) with adaptive threshold — far more noise-resistant than a fixed `argrelextrema` window
- Augmented with LTP (last traded price) pivot for real-time awareness

### Layer Pipeline (ElliottWaveAnalyzer)

| Layer | Class | Purpose |
|-------|-------|---------|
| 4 | `PivotIndicatorEnricher` | Enriches raw pivots with market structure data (trend, strength, segment context) |
| 5 | `PivotPatternDetector` | Detects chart patterns: triangles, wedges, head-and-shoulders, flags — generates `WaveContextHint`s |
| 6 | `WaveCounter` | Generates ALL valid wave counts with Fibonacci ratio scoring (impulse + corrections) |
| 7 | `ScenarioBuilder` | Ranks competing wave counts into scored scenarios |
| — | `GapDetectorService` | Detects significant price gaps across timeframes |
| — | `NestedCorrectiveContextBuilder` | Builds nested corrective structure (W4→ABC→nested zigzag etc.) |
| — | `VirginTrendlineDetector` | Detects untouched trendlines from the best wave count |

### Advanced Pipeline (AdvancedElliottService)

| Step | Class | Purpose |
|------|-------|---------|
| 1 | `ElliottWaveAnalyzer` | Full 7-layer wave engine above |
| 2 | `MultiDegreePivotBuilder` + `SwingBuilder` | Builds multi-degree pivot hierarchy; classifies micro/medium/major swings |
| 3 | `FibLevelBuilder` | Computes Fibonacci retracement and extension levels from the most recent completed swing |
| 3 | `HorizontalSRBuilder` | Builds horizontal S/R clusters from medium-degree pivots |
| 3 | `ConfluenceAggregator` → `DecisionZoneClassifier` | Aggregates price levels into confluence zones; classifies zones relative to scenarios |
| 4 | `ScenarioStatusTracker` | Scores and ranks scenarios against current price |
| 5 | `CandleTriggerDetector` | Detects recent candle trigger patterns (last N bars) |
| 6 | `EntryBuilder` | Builds entry candidates with stop-loss, target, and risk-reward ratio |
| 7 | `HypothesisManager` | Tracks hypothesis state across multiple runs per symbol/timeframe |

### Fibonacci Ratio Validation (WaveCounter)

```
W2 ideal retrace:   0.618  (±15%)
W3 ideal extension: 1.618  (±15%)
W4 ideal retrace:   0.382  (±15%)
W5 ideal extension: 1.000  (±15%)

Corrective:
  Zigzag B max retrace: 0.786
  Flat B min retrace:   0.900
  C range:              0.618–1.618 of A
```

### Multi-Timeframe Intelligence
- **Top-down TfContext extraction**: best wave count per timeframe summarised into `TfContext` (position, structure type, bullish/bearish bias, W4 support zones, correction origin)
- **Bottom-up confirmation boosts**: child TF zigzag/flat/triangle in W4 territory adds +15 to parent score; child impulse W5 confirms parent Wave A
- **Segment proportionality bonus** (+15 max): W3 in longest segment (+6), W2/W4 alternation (+5), W5 divergence from W3 (+4)
- **Cross-TF narrative**: human-readable narrative across all timeframes fed to AI prompt

### Corrective Wave Types
Full support for: Zigzag (A-B-C), Flat, Expanded Flat, Triangle (symmetrical, ascending, descending), Diagonal

---

## Comparison Table

| Feature | External Repos | Our Engine |
|---------|---------------|------------|
| Pivot detection | `scipy.argrelextrema` fixed window | ZigZag adaptive threshold |
| Wave search | O(n^9) brute-force | Fibonacci-guided scan per timeframe |
| Fibonacci validation | ❌ None | ✅ All five wave ratios with tolerance |
| Corrective types | ❌ None | ✅ Zigzag, Flat, Expanded Flat, Triangle, Diagonal |
| Multi-timeframe | ❌ Single chart | ✅ Up to 4 timeframes, top-down + bottom-up |
| Pattern detection | ❌ None | ✅ Triangles, wedges, H&S, flags |
| Confluence zones | ❌ None | ✅ Fib + S/R + wave scenario overlay |
| Entry builder | ❌ None | ✅ Entry/SL/TP + risk-reward ratio |
| Gap detection | ❌ None | ✅ Minor/Major/Breakaway with fill status |
| Hypothesis tracking | ❌ None | ✅ Persistent per-symbol hypothesis manager |
| Production-ready | ❌ Research notebook | ✅ Spring service, DB-backed, REST API |
| Real-time | ❌ Offline | ✅ LTP pivot, live candle integration |

---

## Opportunities for Improvement

The external repos, despite being simpler, highlight a few ideas worth considering:

### 1. Wave Chaining (Wkemery)
Wkemery chains completed impulse waves into sequences (Wave 1 → Wave 2 → ...) to label multi-wave progressions. Our engine tracks this via `HypothesisManager` but the chaining logic is implicit. An explicit multi-wave sequencer could make the AI prompt more readable.

### 2. Brute-Force Validation Pass as Sanity Check
The external repos use pure structural rules without Fibonacci. Running our pivot sequence through a lightweight structural-rules-only pass (independently of Fibonacci) could catch cases where Fibonacci ratios are loose but the structural geometry is clearly impulsive — useful for extended Wave 3 scenarios.

### 3. Fitness Score Transparency
Our `WaveCount` has `indicatorScore`, `fibScore`, `crossTfScore`. These are summed but not individually exposed to the AI. Serialising each sub-score into the prompt summary would give the AI better calibration signals.

### 4. argrelextrema as Fallback
For instruments with very sparse data or unusual volatility profiles, the fixed-window argrelextrema approach can sometimes find pivots our ZigZag misses. A fallback pivot set from argrelextrema could be used to generate additional candidate counts when the ZigZag pivot list is too short.

---

## Our Implementation: Deep Technical Details

### ZigZag Pivot Detection — State Machine

```
[NONE]
  → (move exceeds dynamic threshold)
    → [UP or DOWN]
      → (reversal exceeds hysteresis = baseThr × 0.3)
        → confirm pivot, flip direction
```

**Dynamic threshold:** `baseThr = max(pctMin × price, atrMult × ATR[i])`
**Volatility scaling (optional):** `baseThr × volMult × RVOL[i]`
Min bars between pivots: 2; Hysteresis: 30%; Max lookback: 1000 bars.

### Layer 4 — Pivot Indicator Enrichment (25+ indicators per pivot)

Each `EnrichedPivot` carries: MACD/histogram, EWO, RSI, Stochastic, Bollinger Bands (pctB, width, walk, squeeze), ADX/DMI, OBV, MFI, volume/relative-volume, EMA20/50/200 + stack order, structure label (HH/HL/LH/LL/BOS/CHoC), retracement%, extension%.

### Wave Scoring Breakdown

| Component | Max Points | Criteria |
|-----------|-----------|---------|
| `fibScore` | 40 | W2 in range (+8), W3 extension (+12), W4 in range (+8), W5 ≈ W1 (+8), W3 longest (+4) |
| `indicatorScore` | 30 | EWO peak at W3, RSI/MACD divergence at W5, Bollinger walk, volume expansion |
| `crossTfScore` | 20 | Child TF structures match parent wave expectations |
| `alternationScore` | 10 | W2 ≠ W4 in type and depth |
| Proportionality bonus | +15 | W3 longest segment (+6), alternation >20% difference (+5), W5 weaker than W3 (+4) |

### Hard vs Soft Validation Rules

| Rule | Type | Consequence |
|------|------|-------------|
| W2 ≤ 100% retrace of W1 | Hard | Count discarded |
| W3 must exceed W1 end | Hard | Count discarded |
| W4 must not enter W1 territory | Hard | Count discarded (diagonal exception) |
| W3 must not be shortest wave | Hard | Added to violations list |
| W1 significance < 8% of range | Soft | −15 fibScore |
| Diagonal: W4 must overlap W1 | Hard | Pattern requires minimum overlap fraction |
| RSI/MACD divergence at W5 | Soft | +indicatorScore bonus |
| Volume expansion at W3 | Soft | +indicatorScore bonus |

### Known Weaknesses / Limitations

1. **Pivot confirmation lag** — Confirmed pivots are always ≥1 bar behind; LTP synthetic pivot mitigates but doesn't eliminate.
2. **±15% Fibonacci tolerance** — Generous; may accept counts that overlap with neighbouring invalid counts.
3. **W3 longest rule is soft** — Score reduction only; W3 can slip as 2nd longest without eliminating the count.
4. **Diagonal scope** — Only scans last 6 pivots (5 swings); expanding diagonals not detected.
5. **Multi-degree nesting is implicit** — No automatic sub-wave mapping from lower TF impulse to parent TF wave (e.g., child W3 ≠ parent sub-wave 3 explicitly).
6. **Confluence zone flat weighting** — Fibonacci retracement, extension, and S/R levels clustered equally; no degree-aware weighting.
7. **Pattern confirmation speed** — BUILDING → WATCHING → CONFIRMED progression requires explicit pivot confirmation; early breakouts can be missed.
8. **AI verification fragility** — `ElliottVerificationService` uses string parsing; malformed JSON silently returns 0.5 confidence.
9. **No TF order validation** — TF array assumed descending degree; wrong ordering would mis-label parent/child relationships.
10. **Indicator divergence is boolean** — No magnitude weighting (e.g., how much worse is W5 RSI vs W3 RSI).
11. **Cache staleness** — ZigZag snapshots use time-based TTL with no explicit invalidation on BOS/CHoC events.
12. **Volume under-integrated** — OBV/MFI computed but minimally used in wave scoring; volume-weighted scoring could improve accuracy.

---

## Improvement Opportunities (Consolidated)

| Priority | Idea | Source |
|----------|------|--------|
| High | Expose sub-scores (fibScore, indicatorScore, crossTfScore, alternationScore) individually in AI prompt for calibration | Our engine analysis |
| High | Magnitude-weighted divergence scoring (how much worse is W5 RSI vs W3 RSI) | Our engine analysis |
| Medium | Cache invalidation on BOS/CHoC events (not just TTL) | Our engine analysis |
| Medium | Volume-weighted wave scoring (OBV expansion, MFI confirmation) | Our engine analysis |
| Medium | Explicit multi-wave chaining/sequencer (link completed waves to next expected) | Wkemery comparison |
| Medium | Structural-rules-only validation pass independent of Fibonacci (catches clear geometric patterns) | External repo comparison |
| Low | Stricter W3-longest enforcement as optional mode | Our engine analysis |
| Low | argrelextrema fallback pivot set for sparse data | External repo comparison |
| Low | Degree-aware confidence weighting in confluence zone aggregation | Our engine analysis |

---

*Generated: 2026-03-31*
