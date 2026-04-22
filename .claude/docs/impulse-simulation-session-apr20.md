# Impulse Simulation Session — April 19-20, 2026

## Architecture (Final Working State)

### Training Pipeline (Two-Pass)
```
Pass 1: Full-series ZigZag → confirmed labels (forward-looking ground truth)
  - zigZagService.detect(primarySeries, backtestParams) → confirmed pivots
  - HistoricalRawImpulseLabeler.label() → {wave3_start, no_impulse} at confirmed pivots
  
Pass 2: IncrementalZigZag bar-by-bar → features (backward-looking, what prod sees)
  - IncrementalZigZag.processBar() for each bar
  - At each new IZZ pivot: extract features from trailing extreme
  - MUST call: impulseLabeler.enrichPivots() + zigZagService.computePivotMetrics()
  
Merge: For each IZZ pivot, find nearest confirmed label within ±10 bars
  - {IZZ features at pivot time} + {confirmed label} = training row
```

### Simulation Flow
```
TradeSimulationService:
  - Growing BarSeries per symbol (NOT truncation)
  - Pre-add lookback bars (all data from 2015-01-01 to sim start)
  - Sim loop: add bars one at a time as clock advances

ImpulseSimulationStrategy.scan():
  - IncrementalZigZag per symbol (created lazily on first scan)
  - Processes NEW bars since last call
  - On new pivot: enrich + computePivotMetrics + extract features
  - POST to prediction server → check confidence threshold
  - Must recompute indicators per pivot (NOT cache stale)

ImpulseSimulationStrategy.checkExits():
  - Computes MACD/RSI on truncated series per bar
  - Uses ExitContext with bars counters + indicators
  - Routes to EQ (slab trail) or OPT (fixed target) via ExitStrategyRouter
```

### Key Classes
- `IncrementalZigZag` — processes one bar at a time, maintains ZigZag state
- `Ta4jZigZagBridge` — wraps ta4j ZigZag (NOT used — too simple for impulse)
- `TradeSimulationService` — growing series, market-hours clock, 5-min exit sub-stepping
- `ImpulseSimulationStrategy` — scan + checkExits using IncrementalZigZag
- `ImpulseTrainingDataService` — two-pass pipeline
- `ImpulseSlabExitStrategy` — slab trail with timeout/stall (EQ)
- `ImpulseSlabExitStrategyOpt` — fixed 1.61× target (OPT)

### ZigZag Config (from ChartPatternProperties)
```
atrLength=14, atrMult=0.5, pctMin=0.03, hysteresis=1.1
minBarsBetweenPivots=3, dynamicPctEnabled=true, volMult=2.0, rvolWindow=50
```
IMPORTANT: ta4j's ZigZagStateIndicator doesn't support these params. Must use our custom ZigZag.

### Feature Count: 298
- 20 current pivot (direction, retrace, leg_size, duration, speed + 15 indicators)
- 160 prior 8 pivots (20 each)
- 60 older pivots 9-20 (5 structural each)
- 4 market structure
- 16 HTF + 16 LTF
- 7 derived
- 15 trend segments (5 trends × direction/size/bars)

## Issues Found & Fixed

### 1. Confirmed vs Trailing Pivot Mismatch
- Training used confirmed pivots (full-series ZigZag), simulation used trailing extreme (IncrementalZigZag)
- Fix: two-pass pipeline — labels from confirmed, features from IZZ

### 2. 28 NaN Features (computePivotMetrics)
- IncrementalZigZag doesn't compute retracement/extension percentages
- Fix: made ZigZagService.computePivotMetrics() public, called after IZZ output

### 3. Market Hours Clock
- SimulationClock stepped 24/7, including nights/weekends
- Fix: snap to IST market hours 9:15-15:30, Mon-Fri

### 4. Growing Series (NOT Truncation)
- TradeSimulationService created new BarSeries via truncation each step
- OnChange listeners (ZigZag, indicators) didn't receive new bars
- Fix: persistent growing BarSeries per symbol, addBar() incrementally

### 5. Full History Lookback
- Simulation loaded 1-year lookback, training loaded full history from 2015
- Different ATR → different pivots
- Fix: both load from 2015-01-01

### 6. Indicator Caching (Stale)
- cachedIndicators.computeIfAbsent() computed once, never updated
- Growing series added new bars but indicators stayed stale
- Fix: recompute indicators per pivot (no caching)

### 7. Class Imbalance
- IncrementalZigZag produces ~650 pivots/stock, only ~28 are impulse
- 96% no_impulse → model always predicts no_impulse
- Fix: downsample no_impulse to 2:1 ratio

## Current State (Apr 20 22:15)

### What works:
- Two-pass training pipeline generates correct features+labels
- Model predicts bearish=0.71 for actual impulse rows via API
- Growing series architecture correct
- IncrementalZigZag with computePivotMetrics populates retrace values
- CSV generation: ~60 min for 249 stocks (11 parallel)

### What's broken:
- 2 features still NaN in simulation: leg_duration, leg_speed on trailing extreme
- enrichPivots() needs tsToIdx map entry for trailing extreme timestamp
- The IZZ trailing extreme's timestamp may not exist in the tsToIdx map
- Fix needed: ensure trailing extreme has proper barIndex set

### Next Steps:
1. Fix the 2 NaN features (leg_duration/leg_speed on trailing extreme)
2. Regenerate CSVs + retrain + test simulation
3. If model confidence > 0.90: run 30-stock sweep
4. Compare with Python backtester results

## ta4j Changes (PR #1504)
- BarSeriesListener interface: onBarAdded(index, bar)
- BaseBarSeries fires notifications on addBar()
- CachedIndicator auto-subscribes as listener
- WeakReference prevents memory leaks
- All 5470 tests pass
- Upgraded fork from 0.18 to 0.22.7-SNAPSHOT
