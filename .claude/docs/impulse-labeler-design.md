# Impulse Labeler — Correct Wave Counting Logic

## Date: 2026-04-15 (end of session)

## Wave Counting Rules (from discussion with user)

### Wave 1
- **Start:** CHoCH point (trend flip — e.g., first HH after a downtrend)
- **Measurement:** From CHoCH to the first significant pullback (where correction starts)
- No backward validation needed — wave 1 can be small. What matters is what comes after.

### Wave 2 (complex correction)
- Starts after wave 1's first pullback
- **Can be complex:** nested 1-2-1-2 patterns, mixed structure (LH-HL together)
- **Still in wave 2 if:**
  - Retracements are deep (61%+)
  - Structure is messy/mixed (not a clean trend)
  - A CHoCH happens BUT the wave 1 origin CHoCH level is NOT broken
- **Wave 2 retracement:** 50-99% of wave 1
- **Wave 2 truly ends when:**
  - Price breaks beyond wave 1 territory (clears the wave 1 start level in wave 1's direction)
  - AND retracements become shallow

### Wave 3
- **Start:** When wave 2 ends (price breaks wave 1 origin + shallow retraces + new HH/LL)
- **Measurement:** NOT a single ZigZag leg — it's the full trend segment from wave 3 start until the next trend reversal (CHoCH)
- Multi-leg: any pullback that continues the trend with <50% retrace of prior leg is still part of wave 3
- **Must be >= 1.618x wave 1** (this is non-negotiable)
- Internal retracement check: max 23.6% pullback within wave 3

### Wave 4
- Correction after wave 3
- Retraces 23.6-50% of wave 3
- Must not overlap wave 1 territory

### Wave 5
- Continuation after wave 4
- Must be >= 0.618x wave 3
- Same direction as wave 3

## Key Insight: Trend Segments, Not Individual Legs

The current labeler compares individual ZigZag legs (pivot to pivot). This is WRONG.

Waves are **trend segments** — multiple legs moving in the same direction. Use:
- `MarketStructureService` for HH/HL/LH/LL labels and CHoCH/BOS events
- `TrendSegment` objects for measuring wave sizes
- CHoCH as the wave boundary marker

## Implementation Approach

1. Get all pivots with structure labels from `MarketStructureService`
2. Find CHoCH events → these are potential wave 1 starts
3. For each CHoCH:
   a. Measure wave 1 = trend segment from CHoCH to first significant correction
   b. Track wave 2 = complex correction. Monitor retrace depth and structure.
      Key: wave 2 ends only when wave 1 origin level is breached in wave 1's direction.
   c. If wave 2 valid (50-99% retrace), mark the point as wave 3 start candidate
   d. Measure wave 3 = full trend segment until next reversal
   e. Check wave 3 >= 1.618x wave 1
   f. If yes → label the wave 3 start point as `wave3_start`

4. For wave 5: find wave 4 correction after confirmed wave 3, then measure wave 5

## Current State of Code

- `ImpulseLabeler.java` — exists but uses wrong single-leg logic. Needs full rewrite.
- `ImpulseFeatureExtractor.java` — 271 features, works correctly. No changes needed.
- `ImpulseTrainingDataService.java` — orchestrator, works correctly. No changes needed.
- `ZigZagPoint` — enriched with legSizePct, legDurationBars, legSpeed. Fine.
- `MarketStructureService` — has all the structure labels (CHoCH, BOS, HH/HL/LH/LL, TrendSegments)

## Files to Change

Only `ImpulseLabeler.java` needs rewriting. Everything else is ready.

## Spec Parameters (confirmed)
- wave2_retrace_min: 50%
- wave2_retrace_max: 99%
- wave3_min_ratio: 1.618 (NON-NEGOTIABLE)
- wave3_max_internal_retrace: 23.6%
- wave4_retrace_min: 23.6%
- wave4_retrace_max: 50%
- wave5_min_ratio: 0.618
