# Impulse Simulation Session — April 20-21, 2026

## Changes Made

### 1. Fixed NaN Features (6 features)
- `curr_leg_duration`, `curr_leg_speed` — fallback to `ZigZagPoint.getBarIndex()` when tsToIdx lookup fails
- `htf_trend_state`, `htf_retrace_pct`, `htf_pivot_dist_pct` — computed HTF zigzag + market structure in training pipeline
- `ltf_trend_state`, `ltf_trend_reversal_flag` — computed LTF market structure + CHoCH detection
- `bars_since_large_leg` — default to 0 instead of NaN when no large leg found

### 2. ImpulseFeatureExtractor Changes
- Added 3 new params: `htfPivots`, `htfStructurePoints`, `ltfStructurePoints`
- `fillTfContext()` now computes HTF trend state, retrace%, pivot distance from closest HTF pivot
- `fillLtfContext()` now computes LTF trend state and CHoCH reversal flag
- Added `findClosestPivot()` helper

### 3. ImpulseTrainingDataService Changes
- Computes HTF zigzag pivots + market structure analysis (was missing entirely)
- Computes LTF market structure analysis (was missing)
- Passes all 3 new params to extractFeatures()

### 4. Exit Strategy Tuning
- `impulse.exit.entry.patience.bars`: 50 → 150 (12.5 hours at 5-min bars)
- `impulse.exit.stall.bars`: 25 → 75 (6.25 hours)
- `impulse.exit.max.bars`: 100 → 450 (~5 trading sessions)
- Previous issue: trades exiting via TIMEOUT_EXIT within 4 hours, before reaching first slab

### 5. Model Retrain Results
- 249 FnO stocks, 81,547 rows, 298 features
- Precision: bullish 0.86, bearish 0.85
- Top features: trend5_direction (0.24), ltf_ema50_dist (0.048), curr_direction (0.034)
- htf/ltf features now contributing (htf_stoch_k at #13)

## 30-Stock Simulation Results (threshold=0.30, Jan 2026, IN PROGRESS)

| # | Stock | Dir | Entry | Exit | Exit Type | PnL |
|---|-------|-----|-------|------|-----------|-----|
| 1 | MARUTI | LONG | 16680 | 16930 | TSL_HIT (1×) | +1.50% |
| 2 | RELIANCE | LONG | 1589 | 1585 | STOP_HIT | -0.24% |
| 3 | NTPC | LONG | 332.8 | 348.2 | TSL_HIT (1.382×) | +4.63% |
| 4 | LT | LONG | 4123 | 4175 | TIMEOUT | +1.26% |
| 5 | WIPRO | LONG | 262.2 | 257.2 | STOP_HIT | -1.93% |
| 6 | SUNPHARMA | LONG | 1712.6 | 1736.9 | TIMEOUT | +1.42% |
| 7 | HDFCBANK | LONG | 990 | 988.7 | STOP_HIT | -0.15% |

**7 closed, 4W/3L (57%), +6.49% total PnL, avg win +2.20%, avg loss -0.77%**

Simulation still running (at Jan 5, going to Mar 31).

## Key Improvements Over Previous Session
1. NaN features fixed → model can use HTF/LTF market structure
2. Exit patience 3× longer → trades can reach slab targets
3. NTPC +4.63% via TSL — proper impulse capture
4. Win/loss ratio ~2:1 (wins are bigger than losses)

## Files Modified
- `strategies/impulse/java/ImpulseFeatureExtractor.java`
- `strategies/impulse/java/ImpulseTrainingDataService.java`
- `strategies/impulse/java/ImpulseSimulationStrategy.java`
- `strategies/impulse/java/ImpulseSignalScanner.java`
- `strategies/impulse/java/ImpulseLabeler.java`
- `src/main/resources/application.properties`
