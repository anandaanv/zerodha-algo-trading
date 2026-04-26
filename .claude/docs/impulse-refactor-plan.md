# Impulse Scanner Refactoring Plan

## Problem
`ImpulseSignalScanner` (live) and `ImpulseSimulationStrategy` (sim) have duplicated logic that drifted apart. The simulation got all the fixes (HTF/LTF, direction, pullback entry, new SL) but the live scanner is still on old code.

## Right Approach: Shared Core

### `ImpulseScannerCore` (new class)
Common logic extracted:
- HTF/LTF data loading + caching per symbol
- Feature extraction (303 features)
- Prediction call + direction logic (max(bull, bear))
- Pullback entry calculation (2-candle midpoint)
- SL calculation (lowest low of 2 candles)
- Target calculation (7% move)
- Signal building (TradeSignal)

### `ImpulseSignalScanner` extends/uses Core
- @Scheduled cron (every 15 min at :01, :16, :31, :46)
- FnO symbol list
- Trade count check per symbol
- DB save (TradeSignalRepository)
- Order placement (TradeOrchestrationService)
- Paper trade mode

### `ImpulseSimulationStrategy` uses Core
- SimulationContext / BarSeries input
- IncrementalZigZag (vs snapshot zigzag in live)
- No DB save, returns signals directly
- No order placement

### Key Differences to Handle
1. **ZigZag source**: Live uses `zigZagService.getOrComputePivots()`, Sim uses `IncrementalZigZag`
2. **Bar data**: Live loads from DB, Sim uses growing BarSeries
3. **Output**: Live saves to DB + places orders, Sim returns List<TradeSignal>
4. **Scheduling**: Live is cron-scheduled, Sim is called per step

### Interface
```java
public interface ImpulseScanEngine {
    // Core scanning: pivots + features → prediction → signal
    Optional<TradeSignal> evaluate(
        String symbol,
        List<ZigZagPoint> pivots,
        BarSeries barSeries,
        Map<Instant, Integer> tsToIdx,
        // HTF/LTF data (nullable, computed by engine if null)
        ...
    );
}
```

## Session State (Apr 22)
- Live scanner updated with quick fixes BUT user wants proper refactor
- Revert the quick fix, do it right
- Deploy script working on EC2
- Both models (price-jump + Elliott) ready
- Production: prediction server running, Tomcat deployed
- impulse.enabled=false (safe)
