# Codebase Refactoring Plan

## Overview

This document outlines a comprehensive plan to restructure the zerodha-algo-trading codebase from its current monolithic layout into a clean, modular architecture. Every change is a clean checkpoint — everything working before and after.

## Branch Strategy

- **`master`** — stable production branch. No refactoring merges until fully tested.
- **`cleanup_refactor`** — long-lived feature branch. All refactoring work happens here.
- Each GitHub issue is implemented as a sub-branch off `cleanup_refactor`, PR'd back into `cleanup_refactor`.
- Once all phases are complete and stable, `cleanup_refactor` is merged into `master`.

```
master ─────────────────────────────────────────────────────► (stable)
   └── cleanup_refactor ────────────────────────────────────► (merge to master when done)
           ├── issue/101-integration-tests
           ├── issue/102-delete-dead-code
           ├── issue/103-unify-enums
           └── ...
```

## Current State

### Codebase Stats
- 714 Java files in `src/main/java/com/dtech/`
- 8 top-level packages: `algo`, `kitecon`, `trade`, `ta`, `elliott`, `chartpattern`, `chartdata`, `dhan`
- `kitecon` alone has 370 files — a monolith within a monolith

### Critical Issues

#### 1. Duplicate Functionality
| What | Where | Status |
|------|-------|--------|
| `TradeDirection` enum | `kitecon.strategy` (Buy/Sell/Both) vs `kitecon.trade.enums` (LONG/SHORT) | **TYPE CONFLICT** |
| `OrderManager` interface | `kitecon.market.orders` vs `trade.order` | **TWO CONTRACTS** |
| `BacktestResult` + `TradeRecord` + `OrderRecord` | `algo.backtest` + `kitecon.strategy.backtest` | **IDENTICAL DEAD CODE** |
| `StrategyConfig` | `algo.strategy.config` vs `kitecon.strategy.builder` | **INCOMPATIBLE** |
| `StrategyService` | `algo.service` (empty) vs `kitecon.service` (active) | **DEAD CODE** |
| `DataLoader` | `kitecon.loader` (empty stub) | **DEAD CODE** |
| `ActiveOrderManager` | `trade/` (incomplete, empty methods) | **DEAD CODE** |

#### 2. No Shared Strategy Interface
DTB, Elliott, and PriceJump strategies have no common interface. Each has its own:
- Signal detection logic
- Scanner (cron-driven)
- Exit strategy
- Backtest integration

#### 3. Three Independent Screener Systems
1. `algo.screener` (56 files) — Generic Kotlin DSL screener
2. `kitecon.patternscanner` (15 files) — Pattern combo scanner (DTB)
3. `kitecon.scan` (18 files) — On-demand scan

No shared interfaces, separate entity models, separate repositories.

#### 4. Bidirectional Coupling
`kitecon` imports `algo` 84 times. `algo` imports `kitecon` 57 times. Circular dependency prevents clean modularization.

#### 5. Two Data Loader Hierarchies
- `algo.strategy.units.ZerodhaBarSeriesLoader` (newer)
- `kitecon.strategy.dataloader.BarsLoader` (active)
- `kitecon.loader.DataLoader` (empty stub)

#### 6. Multiple AI/Copilot Entry Points
- `kitecon.service.copilot` — orchestrator layer
- `kitecon.service.ai` — provider abstraction
- `algo.service.OpenAIScreenService` — direct calls

---

## Target Architecture

```
com.dtech/
├── core/                        # ZERO framework deps — shared by everything
│   ├── model/                   # Instrument, Candle, Bar
│   ├── enums/                   # TradeDirection (LONG/SHORT), TradingSegment, StrategyType
│   ├── series/                  # Interval, ExtendedBarSeries
│   ├── dto/                     # QuoteResult, ResolvedInstrument
│   └── strategy/                # Strategy, SignalDetector, ExitStrategy interfaces
│
├── market/                      # Broker integrations — depends on core only
│   ├── facade/                  # MarketFacade, MarketFacadeProvider
│   ├── zerodha/                 # Kite: auth, quotes, orders, ticker
│   ├── dhan/                    # Dhan broker
│   └── orders/                  # Unified OrderManager
│
├── analysis/                    # Technical analysis — depends on core + ta4j
│   ├── zigzag/                  # ZigZag pivots, market structure
│   ├── elliott/                 # Elliott Wave labeling
│   ├── trendline/               # Trendline detection
│   ├── patterns/                # DTB patterns (double top/bottom, triangle, etc)
│   ├── divergences/             # RSI/MACD divergences
│   └── features/                # ML feature extraction
│
├── strategy/                    # Strategy framework — depends on core + analysis
│   ├── api/                     # SignalDetector, SignalScanner, ExitStrategy interfaces
│   ├── dtb/                     # DTB strategy impl
│   ├── impulse/                 # PriceJump + Elliott impulse impl
│   ├── screener/                # Kotlin DSL screener
│   ├── backtest/                # Unified backtesting framework
│   └── simulation/              # Trade simulation
│
├── trading/                     # Order lifecycle — depends on core + market
│   ├── orchestration/           # TradeOrchestrationService
│   ├── execution/               # Paper + live order execution
│   ├── monitor/                 # TradeMonitorWorker
│   ├── capital/                 # CapitalAllocationService
│   └── exit/                    # ExitStrategyRouter + all exits
│
├── data/                        # Data layer — depends on core
│   ├── entity/                  # All JPA entities
│   ├── repository/              # All repositories
│   ├── loader/                  # Unified BarSeries loading
│   └── sync/                    # Candle sync, data download
│
├── ai/                          # AI services — depends on core
│   ├── provider/                # AIProvider interface, OpenAI impl
│   ├── copilot/                 # Market analysis copilot
│   └── prediction/              # ML prediction client (HTTP to Python)
│
├── api/                         # Thin REST + config — depends on everything
│   ├── controller/              # All REST controllers
│   ├── auth/                    # JWT, OAuth, Spring Security
│   ├── websocket/               # WebSocket handlers
│   └── config/                  # Spring Boot, Swagger
│
├── ui/                          # React frontend (existing)
├── ds-python/                   # ML training (existing)
└── strategies/                  # Private submodule (existing)
```

### Shared Strategy Interface

All strategies (DTB, Elliott, PriceJump, future ones) implement:

```java
// Signal detection
interface SignalDetector {
    String getStrategyName();
    Optional<DetectedSignal> detect(AnalysisContext context);
}

// Periodic scanning (cron-driven)
interface SignalScanner {
    List<DetectedSignal> scan(List<String> symbols, Interval timeframe);
    String getCronExpression();
}

// Exit management
interface ExitStrategy {
    ExitDecision evaluate(ExitContext context);
    String getStrategyName();
}

// Shared context
record AnalysisContext(String symbol, BarSeries series, List<ZigZagPoint> pivots,
                       Map<Instant, Integer> tsToIdx, Interval interval) {}
```

---

## Implementation Phases

### Phase 0: Baseline — Integration Test Coverage
**Goal:** Comprehensive tests that verify all critical functionality BEFORE refactoring starts. These tests serve as a safety net — if they pass after a refactor, we haven't broken anything.

**Test coverage needed:**
1. **Trade lifecycle** — signal creation → instrument resolution → order placement → exit
2. **Scanner cron** — DTB pattern scanner finds signals and creates TradeSignals
3. **Impulse pipeline** — ImpulseAnalysisCore.analyze() returns correct signals
4. **Instrument resolution** — EQ/FUT/OPT resolution with BSE fallback
5. **Capital allocation** — Margin fetch + quantity computation
6. **Live P&L** — LTP enrichment on open orders
7. **Exit strategies** — PriceJumpExitStrategy, ImpulseSlabExitStrategy, DtbExitStrategy
8. **REST API** — Key endpoints return correct data
9. **ZigZag** — Pivot detection produces consistent results
10. **Feature extraction** — ImpulseFeatureExtractor produces correct feature count

### Phase 1: Delete Dead Code
**Goal:** Remove confirmed dead code. No functional changes.

Files to delete:
- `algo/backtest/BacktestResult.java`
- `algo/backtest/TradeRecord.java`
- `algo/backtest/OrderRecord.java`
- `kitecon/strategy/backtest/BacktestResult.java`
- `kitecon/strategy/backtest/TradeRecord.java`
- `kitecon/strategy/backtest/OrderRecord.java`
- `algo/service/StrategyService.java` (empty shell)
- `trade/ActiveOrderManager.java` (incomplete)
- `kitecon/loader/DataLoader.java` (empty stub)

Verify: `./gradlew test` passes, no compile errors.

### Phase 2: Unify Enums and Core Types
**Goal:** Single source of truth for shared types.

1. **Unify `TradeDirection`** — keep `LONG/SHORT` (in `kitecon.trade.enums`), migrate all `Buy/Sell/Both` references
2. **Audit `TradeStatus`** — check `algo.screener.trade.TradeStatus` vs `kitecon.trade.enums.TradeStatus`
3. **Move shared enums to `core.enums`** package

### Phase 3: Extract Core Module
**Goal:** Create `core/` package with zero-dependency interfaces and DTOs.

1. Move shared enums: `TradeDirection`, `TradingSegment`, `StrategyType`, `ExitReason`, `Interval`
2. Move shared DTOs: `QuoteResult`, `ResolvedInstrument`
3. Extract interfaces: `SignalDetector`, `SignalScanner`, `ExitStrategy`
4. Move shared models: `ZigZagPoint`, `MarketStructurePoint`, `TrendSegment`

### Phase 4: Consolidate Order Management
**Goal:** Single `OrderManager` interface and implementation.

1. Delete `trade.order.OrderManager` interface (stale)
2. Merge `KiteOrderManager` and `ZerodhaOrderManager` into one
3. Remove `ActiveOrderManager` (already deleted in Phase 1)
4. All order placement goes through unified interface

### Phase 5: Consolidate Data Loading
**Goal:** Single bar series loading pipeline.

1. Keep `kitecon.strategy.dataloader.BarsLoader` as canonical
2. Make `algo.strategy.units.ZerodhaBarSeriesLoader` delegate to it
3. Remove `kitecon.loader.DataLoader` (already deleted in Phase 1)

### Phase 6: Shared Strategy Interface
**Goal:** DTB, Elliott, PriceJump share common interfaces.

1. Create `strategy.api` package with `SignalDetector`, `SignalScanner`, `ExitStrategy`
2. Refactor DTB scanner to implement `SignalScanner`
3. Refactor `ImpulseSignalScanner` to implement `SignalScanner`
4. Refactor all exit strategies to implement unified `ExitStrategy`

### Phase 7: Consolidate Screener Systems
**Goal:** Shared base for all screener/scanner systems.

1. Extract `Screener` interface from commonalities
2. DTB pattern scanner, on-demand scan, Kotlin screener all implement it
3. Shared entity model for scan results

### Phase 8: Break `kitecon` Monolith
**Goal:** Distribute 370 files from `kitecon` into proper modules.

1. `kitecon.market` → `market/`
2. `kitecon.trade` → `trading/`
3. `kitecon.data` + `kitecon.repository` → `data/`
4. `kitecon.controller` + `kitecon.web` → `api/`
5. `kitecon.service.copilot` + `kitecon.service.ai` → `ai/`
6. `kitecon.patternscanner` → `strategy/dtb/`
7. `kitecon.screener.elliott` → `strategy/impulse/`
8. `kitecon.simulation` → `strategy/simulation/`
9. `kitecon.backtest` → `strategy/backtest/`

### Phase 9: Consolidate Analysis Layer
**Goal:** Merge overlapping TA packages.

1. Merge `ta.elliott` + `elliott.advanced` → `analysis/elliott/`
2. Move `chartpattern.zigzag` → `analysis/zigzag/`
3. Move `ta.trendline` → `analysis/trendline/`
4. Move `ta.patterns` → `analysis/patterns/`
5. Move `ta.divergences` → `analysis/divergences/`

### Phase 10: Clean Up AI Layer
**Goal:** Clear hierarchy for AI services.

1. `AIProvider` interface stays in `ai/provider/`
2. Copilot orchestrator in `ai/copilot/`
3. Remove direct OpenAI calls from `algo.service`

---

## Validation Criteria

After each phase:
1. `./gradlew compileJava` — no compile errors
2. `./gradlew test` — all tests pass (including integration tests from Phase 0)
3. Manual smoke test: scanner runs, orders place, P&L shows on UI
4. `graphify update .` — graph structure reflects changes

## Risk Mitigation

- **Every phase is on its own branch** off `cleanup_refactor`
- **Integration tests from Phase 0** run after every phase
- **No changes to `master`** until all phases complete
- **Each phase is independently reviewable** via PR

## Timeline Estimate

| Phase | Scope | Size |
|-------|-------|------|
| 0 | Integration tests | Medium |
| 1 | Delete dead code | Small |
| 2 | Unify enums | Small |
| 3 | Extract core | Medium |
| 4 | Consolidate orders | Small |
| 5 | Consolidate data loading | Small |
| 6 | Shared strategy interface | Medium |
| 7 | Consolidate screeners | Large |
| 8 | Break kitecon monolith | Large |
| 9 | Consolidate analysis | Medium |
| 10 | Clean up AI | Small |
