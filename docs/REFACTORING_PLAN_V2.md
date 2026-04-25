# Zerodha Algo Trading — Comprehensive Refactoring Plan

**Date**: 2026-04-24  
**Codebase**: 713 Java files, ~71K LOC, 9 top-level packages  
**Graphify**: 5307 nodes, 13598 edges, 235 communities  

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Current State Analysis](#2-current-state-analysis)
3. [Target Architecture](#3-target-architecture)
4. [Phase 1 — Break Monoliths](#4-phase-1--break-monoliths)
5. [Phase 2 — Unify Duplicate Subsystems](#5-phase-2--unify-duplicate-subsystems)
6. [Phase 3 — Clean Package Boundaries](#6-phase-3--clean-package-boundaries)
7. [Phase 4 — Introduce Missing Abstractions](#7-phase-4--introduce-missing-abstractions)
8. [Phase 5 — Infrastructure Hardening](#8-phase-5--infrastructure-hardening)
9. [Dependency Map (Before & After)](#9-dependency-map-before--after)
10. [Risk Register & Rollback Strategy](#10-risk-register--rollback-strategy)
11. [Execution Order & Priority Matrix](#11-execution-order--priority-matrix)

---

## 1. Executive Summary

The codebase has grown organically around two main packages — `com.dtech.algo` (166 files) and `com.dtech.kitecon` (369 files) — with significant duplication in screener, strategy, analysis, and Elliott Wave subsystems. Several services exceed 1000 LOC with mixed responsibilities, and the package structure doesn't reflect clean domain boundaries.

**Top 5 problems this refactor solves:**

| # | Problem | Impact |
|---|---------|--------|
| 1 | `kitecon` is a 41K-line catch-all package | Impossible to reason about dependencies |
| 2 | 3 separate screener systems with no shared interface | Feature drift, duplicated bug fixes |
| 3 | 3 separate Elliott Wave implementations | Same — plus confusion about which to use |
| 4 | Monolithic services (CopilotSkillService: 2766 LOC, TradingViewChartService: 1398 LOC) | Untestable, high merge-conflict rate |
| 5 | Fat controllers with business logic (CopilotAnalysisController: 791 LOC) | Breaks layered architecture |

---

## 2. Current State Analysis

### 2.1 Package Overview

```
com.dtech
├── algo/          (166 files, 11K LOC)  — Screener DSL, strategy builder, chart service, alerts
├── kitecon/       (369 files, 41K LOC)  — EVERYTHING ELSE: copilot, trade, screener, analysis, auth, market
├── ta/            (90 files, 12K LOC)   — Technical analysis: Elliott, divergence, trendline, patterns
├── elliott/       (40 files, 1.6K LOC)  — Advanced Elliott Wave scenario filtering
├── chartpattern/  (17 files, 1.5K LOC)  — ZigZag-based chart pattern recognition
├── dhan/          (12 files, 1.1K LOC)  — Dhan broker integration
├── drawings/      (5 files, 206 LOC)    — Chart drawing annotations
├── chartdata/     (3 files, 158 LOC)    — Chart data management
├── trade/         (9 files, 488 LOC)    — Generic trade utilities
└── swagger/       (2 files, 63 LOC)     — API docs config
```

### 2.2 Monolithic Services (>500 LOC)

| Service | LOC | Responsibilities |
|---------|-----|-----------------|
| `CopilotSkillService` | 2,766 | Skill CRUD + prompt building + section formatting + scan prompts |
| `TradingViewChartService` | 1,398 | Chart analysis + indicator computation + data transformation + formatting |
| `CopilotAnalysisController` | 791 | REST endpoints + orchestration logic + response assembly |
| `OpenAIProviderService` | 667 | HTTP client + response parsing + tool dispatch + streaming |
| `SuggestionChartLayoutService` | 666 | Layout generation + indicator mapping + series construction |
| `StockAnalysisService` | 571 | Multi-indicator analysis + scoring + report generation |
| `TriangleValidationTool` | 542 | AI tool with embedded analysis logic |
| `ChartSnapshotService` | 457 | Snapshot CRUD + tagging + search + permissions |
| `AlertQueueService` | 438 | Alert lifecycle + WhatsApp dispatch + dedup + rate limiting |
| `ElliottScreenerService` | 417 | Screening + scheduling + result persistence |

### 2.3 Duplicate Subsystems

#### A. Three Screener Systems
```
algo.screener/           (56 files)  — Kotlin DSL screener with rule engine
kitecon.screener.elliott/ (27 files) — Elliott Wave screener with scheduling
kitecon.patternscanner/  (15 files)  — ZigZag pattern screener
```
**Problem**: No shared `Screener` interface. Each has its own entity model, repository, controller, scheduler, and result format. Adding a new screener type means copying an entire subsystem.

#### B. Three Elliott Wave Implementations
```
ta.elliott/              (52 files)  — Core Elliott Wave detection (swing, decomposition, hypothesis)
elliott.advanced/        (40 files)  — Scenario filtering pipeline (classify, compress, prune, score)
kitecon.screener.elliott/ (12 files) — Elliott screening + suggestion generation
```
**Problem**: `ta.elliott` and `elliott.advanced` should be a single cohesive library. The screening layer in `kitecon` should consume, not re-implement.

#### C. Two Strategy Frameworks
```
algo.strategy/           (33 files)  — StrategyConfig, IndicatorConfig, RuleConfig, CachedBuilders
kitecon.strategy/        (21 files)  — StrategyBuilder interface, DataLoader, BacktestStrategy
```
**Problem**: Two independent strategy configuration and execution pipelines for what should be a single concern.

#### D. Overlapping Analysis Services
```
algo.service.ChartAnalysisService      (357 LOC) — Chart-level analysis
algo.service.TradingViewChartService   (1,398 LOC) — TradingView-specific analysis + indicators
kitecon.service.StockAnalysisService   (571 LOC) — Stock-level analysis with scoring
kitecon.analysis/                       (12 files, 3K LOC) — AI payload generation, prompt building
```
**Problem**: "Analysis" is scattered across 4 locations with no clear boundary between chart-analysis, stock-analysis, and AI-prompt-generation.

### 2.4 Missing Interfaces

| Missing Abstraction | Current State |
|---------------------|--------------|
| `Screener<T>` interface | 3 independent implementations with no common contract |
| `AnalysisEngine` interface | Analysis logic spread across 4 service classes |
| `BrokerFacade` unified interface | Zerodha and Dhan each have their own facade hierarchy |
| `AlertChannel` interface | WhatsApp hardcoded in AlertQueueService |
| Event bus / domain events | Tight coupling between services via direct method calls |
| Strategy execution pipeline | Two parallel frameworks, neither complete |

---

## 3. Target Architecture

### 3.1 Target Package Structure

```
com.dtech
├── core/                    — Shared domain: Candle, Instrument, Interval, enums
│   ├── model/
│   ├── repository/
│   └── config/
│
├── broker/                  — Broker abstraction layer
│   ├── api/                 — BrokerFacade, OrderManager, MarketDataFetch interfaces
│   ├── zerodha/             — Zerodha implementation
│   └── dhan/                — Dhan implementation
│
├── market/                  — Market data pipeline
│   ├── provider/            — MarketDataProvider interface + impls
│   ├── candle/              — CandleFacade, sync, persistence
│   └── subscription/        — Subscription management, UOW worker
│
├── ta/                      — Technical Analysis library (pure, no Spring)
│   ├── elliott/             — Unified Elliott Wave (current ta.elliott + elliott.advanced)
│   ├── divergence/
│   ├── trendline/
│   ├── pattern/             — Chart pattern recognition (absorbs chartpattern/)
│   └── indicator/           — Indicator computation
│
├── screener/                — Unified screener framework
│   ├── api/                 — Screener<T> interface, ScreenerResult, ScreenerSchedule
│   ├── dsl/                 — Kotlin DSL screener (from algo.screener)
│   ├── elliott/             — Elliott Wave screener (consumes ta.elliott)
│   ├── pattern/             — Pattern screener (consumes ta.pattern)
│   └── runtime/             — Execution engine, scheduling, persistence
│
├── strategy/                — Unified strategy framework
│   ├── config/              — StrategyConfig, IndicatorConfig, RuleConfig
│   ├── builder/             — Strategy building pipeline
│   ├── runner/              — Strategy execution
│   ├── backtest/            — Backtesting framework
│   └── simulation/          — Trade simulation
│
├── trade/                   — Trade execution lifecycle
│   ├── signal/              — Signal generation, TradeSignal entity
│   ├── entry/               — Entry handling, capital allocation
│   ├── execution/           — Order execution, broker integration
│   ├── monitor/             — Position monitoring, SL/TP tracking
│   ├── exit/                — Exit strategies (ExitStrategy interface + impls)
│   └── entity/              — TradeOrder, TradeExecution, TradeActionLog
│
├── analysis/                — Chart & stock analysis
│   ├── chart/               — ChartAnalysisService (merge of 3 current services)
│   ├── stock/               — StockAnalysisService (focused scoring)
│   └── snapshot/            — ChartSnapshot CRUD, tagging, search
│
├── copilot/                 — AI Copilot system
│   ├── ai/                  — AIProvider interface, OpenAI/Anthropic impls
│   ├── orchestrator/        — CopilotOrchestratorService
│   ├── skill/               — SkillService (CRUD only) + SkillPromptBuilder (separate)
│   ├── investigation/       — Hypothesis, Observation, Investigation services
│   ├── monitoring/          — AI-powered trade monitoring
│   ├── tool/                — AI tools (TriangleValidation, TrendlineBreakout)
│   └── dto/                 — All copilot DTOs
│
├── alert/                   — Unified alerting
│   ├── api/                 — AlertChannel interface
│   ├── whatsapp/            — WhatsApp implementation
│   ├── websocket/           — WebSocket push
│   └── queue/               — Alert queue, dedup, rate limiting
│
├── auth/                    — Authentication & authorization
│   ├── jwt/                 — JWT filter, token service
│   ├── oauth/               — Google OAuth, Kite OAuth
│   └── user/                — User entity, UserRepository
│
├── chart/                   — Chart UI support
│   ├── drawing/             — Drawing annotations (absorbs drawings/)
│   ├── layout/              — Chart layouts
│   ├── state/               — Chart state persistence
│   └── tradingview/         — TradingView integration
│
├── web/                     — All REST controllers (thin, delegate to services)
│   ├── copilot/
│   ├── trade/
│   ├── screener/
│   ├── chart/
│   ├── analysis/
│   ├── admin/
│   └── config/              — CORS, WebMvc, Swagger
│
└── config/                  — Application-wide config
    ├── SecurityConfig
    ├── AsyncConfig
    ├── SchedulingConfig
    ├── CachingConfig
    ├── WebSocketConfig
    └── DatabaseSecretsPostProcessor
```

### 3.2 Key Interfaces to Introduce

```java
// --- Broker Abstraction ---
public interface BrokerFacade {
    List<Candle> fetchHistoricalData(String instrument, Interval interval, LocalDate from, LocalDate to);
    Quote getQuote(String instrument);
    OrderResponse placeOrder(OrderRequest request);
    OrderResponse modifyOrder(String orderId, OrderModification modification);
    void cancelOrder(String orderId);
    List<Position> getPositions();
}

// --- Screener Framework ---
public interface Screener<T extends ScreenerResult> {
    String getName();
    ScreenerType getType();
    List<T> scan(List<Instrument> instruments, ScreenerConfig config);
    boolean supports(ScreenerType type);
}

public interface ScreenerScheduler {
    void schedule(String screenerId, CronExpression cron);
    void cancel(String screenerId);
    List<ScheduledScreener> listScheduled();
}

// --- Market Data ---
public interface MarketDataProvider {
    BarSeries getBarSeries(Instrument instrument, Interval interval, LocalDate from, LocalDate to);
    Optional<Quote> getLatestQuote(Instrument instrument);
}

// --- Analysis ---
public interface AnalysisEngine<I, O> {
    O analyze(I input);
    AnalysisType getType();
}

// --- Alert ---
public interface AlertChannel {
    void send(Alert alert);
    boolean supports(AlertType type);
}

// --- AI Provider ---
public interface AIProvider {
    AIResponse chat(List<Message> messages, AIConfig config);
    AIResponse chatWithTools(List<Message> messages, List<AITool> tools, AIConfig config);
    Stream<AIResponse> stream(List<Message> messages, AIConfig config);
}

// --- Exit Strategy ---
public interface ExitStrategy {
    ExitDecision evaluate(ExitContext context);
    String getName();
}
```

---

## 4. Phase 1 — Break Monoliths

**Goal**: Split oversized classes into focused, single-responsibility components. No package moves yet — just class-level surgery.

### 4.1 Split CopilotSkillService (2,766 LOC → 3 classes)

**Current**: One class handles CRUD, prompt building, section formatting, and scan-specific prompt variants.

**Target**:
| New Class | Responsibility | Est. LOC |
|-----------|---------------|----------|
| `CopilotSkillCrudService` | CRUD operations: create, read, update, delete, list, search | ~400 |
| `SkillPromptBuilder` | Full analysis prompt construction, section formatting | ~1,200 |
| `ScanPromptBuilder` | Scan-specific prompt variants, short-form prompts | ~600 |

**Extraction steps**:
- [ ] Create `SkillPromptBuilder` — move all `buildPrompt*`, `formatSection*`, `buildIndicatorSection`, `buildPivotSection` methods
- [ ] Create `ScanPromptBuilder` — move `buildScanPrompt*`, `buildQuickScan*` methods
- [ ] Rename remaining class to `CopilotSkillCrudService`
- [ ] Both prompt builders receive `CopilotSkillCrudService` as a dependency (not the other way)
- [ ] Update `CopilotSkillController` to inject the correct builder based on endpoint

### 4.2 Split TradingViewChartService (1,398 LOC → 3 classes)

**Current**: Chart data fetching + indicator computation + data transformation + TradingView-specific formatting.

**Target**:
| New Class | Responsibility | Est. LOC |
|-----------|---------------|----------|
| `ChartDataService` | Fetch and assemble BarSeries data for a given symbol/interval | ~300 |
| `IndicatorComputeService` | Compute all technical indicators on a BarSeries | ~500 |
| `TradingViewFormatter` | Format indicator output for TradingView JSON protocol | ~400 |

**Extraction steps**:
- [ ] Extract data-fetching methods into `ChartDataService`
- [ ] Extract indicator computation (SMA, EMA, RSI, MACD, Bollinger, etc.) into `IndicatorComputeService`
- [ ] Keep TradingView-specific JSON formatting in `TradingViewFormatter`
- [ ] Wire: Controller → `TradingViewFormatter` → `IndicatorComputeService` → `ChartDataService`

### 4.3 Slim Down CopilotAnalysisController (791 LOC → <200 LOC)

**Current**: Controller contains orchestration logic, response assembly, and error-handling business rules.

**Target**: Thin controller that delegates everything to `CopilotAnalysisFacade`.

- [ ] Create `CopilotAnalysisFacade` service — absorb all business logic from the controller
- [ ] Controller methods become 3-5 line delegations: validate input → call facade → return response
- [ ] Move complex DTO assembly logic into the facade

### 4.4 Split AlertQueueService (438 LOC → 2 classes)

- [ ] Extract `WhatsAppAlertSender` — HTTP calls to WhatsApp Business API
- [ ] Keep `AlertQueueService` as the queue/dedup/rate-limiting orchestrator
- [ ] `AlertQueueService` uses `AlertChannel` interface; `WhatsAppAlertSender` implements it

### 4.5 Split ChartSnapshotService (457 LOC → 2 classes)

- [ ] Extract `SnapshotSearchService` — search, filter, tag-based queries
- [ ] Keep `ChartSnapshotService` as CRUD + permission checks

**Validation**: After Phase 1, no class exceeds 600 LOC. All existing tests still pass.

---

## 5. Phase 2 — Unify Duplicate Subsystems

### 5.1 Unified Screener Framework

**Current state**: 3 independent screener implementations (98 files total).

**Step 1 — Define the interface**:
```java
public interface Screener<T extends ScreenerResult> {
    String getName();
    ScreenerType getType();  // DSL, ELLIOTT, PATTERN
    List<T> scan(List<Instrument> instruments, ScreenerConfig config);
}
```

**Step 2 — Adapt existing screeners**:
- [ ] `DslScreener implements Screener<DslScreenerResult>` — wraps existing `algo.screener` runtime
- [ ] `ElliottScreener implements Screener<ElliottScreenerResult>` — wraps `kitecon.screener.elliott`
- [ ] `PatternScreener implements Screener<PatternScreenerResult>` — wraps `kitecon.patternscanner`

**Step 3 — Unified scheduling and persistence**:
- [ ] Create `ScreenerSchedulerService` — manages cron schedules for all screener types
- [ ] Create `ScreenerRunRepository` — unified run history (screener_type + run_id + results JSON)
- [ ] Create `ScreenerController` — single REST resource `/api/screeners/{type}/run`, `/api/screeners/{type}/results`

**Step 4 — Deprecate old endpoints** (keep for 1 release, then remove):
- Mark old Elliott and Pattern screener controllers as `@Deprecated`

### 5.2 Unified Elliott Wave Library

**Current**: `ta.elliott` (52 files — detection) + `elliott.advanced` (40 files — filtering) are separate packages.

**Target**: Merge into `com.dtech.ta.elliott` with clear subpackages:

```
ta.elliott/
├── detection/       — Swing analysis, decomposition, hypothesis generation (from ta.elliott)
├── scenario/        — Scenario objects (from elliott.advanced.domain.scenario)
├── filter/          — Scenario filtering pipeline (from elliott.advanced.scenario.filter)
│   ├── classify/
│   ├── compress/
│   ├── prune/
│   ├── score/
│   └── orchestration/  — FilterOrchestrator
├── trigger/         — Entry/exit trigger logic
└── confluence/      — Cross-timeframe confluence
```

**Steps**:
- [ ] Move `elliott.advanced.scenario.filter.*` → `ta.elliott.filter.*`
- [ ] Move `elliott.advanced.domain.*` → `ta.elliott.scenario.*`
- [ ] Move `elliott.advanced.common.*` → `ta.elliott.model.*`
- [ ] Update all imports (IDE refactor)
- [ ] Delete empty `com.dtech.elliott` package
- [ ] `kitecon.screener.elliott` remains as a *consumer* — it should call `ta.elliott` APIs, not duplicate them

### 5.3 Unified Strategy Framework

**Current**: `algo.strategy` (config/builder) and `kitecon.strategy` (execution/backtest) are disconnected.

**Target**: Single `com.dtech.strategy` package:

```
strategy/
├── config/     — StrategyConfig, IndicatorConfig, RuleConfig (from algo.strategy.config)
├── builder/    — FinalStrategyBuilder, CachedIndicatorBuilder (from algo.strategy)
├── runner/     — StrategyRunner, BacktestRunner (from algo.runner + kitecon.strategy)
├── backtest/   — BacktestService, BacktestResult (from kitecon.backtest)
├── simulation/ — SimulationStrategy, TradeSimulationService (from kitecon.simulation)
└── dataloader/ — BarSeriesLoader, CandleSyncExecutor (from algo.strategy.units + kitecon.strategy.dataloader)
```

**Steps**:
- [ ] Move `algo.strategy.config.*` → `strategy.config.*`
- [ ] Move `algo.strategy.builder.*` → `strategy.builder.*`
- [ ] Move `algo.strategy.units.*` → `strategy.builder.*` (merge)
- [ ] Move `kitecon.strategy.*` → `strategy.runner.*` / `strategy.backtest.*`
- [ ] Move `kitecon.backtest.*` → `strategy.backtest.*`
- [ ] Move `kitecon.simulation.*` → `strategy.simulation.*`
- [ ] Delete old packages

### 5.4 Merge Overlapping Analysis Services

**Target**: Two clear services instead of four overlapping ones:

| New Service | Absorbs | Responsibility |
|-------------|---------|---------------|
| `ChartAnalysisService` | `algo.service.ChartAnalysisService` + `algo.service.TradingViewChartService` (post Phase 1 split) | Indicator computation, chart-level analysis |
| `StockScoringService` | `kitecon.service.StockAnalysisService` | Stock-level scoring and ranking |
| `AIPromptService` | `kitecon.analysis.*` (12 files) | AI payload generation, prompt building — SEPARATE from analysis |

- [ ] Rename existing `kitecon.service.StockAnalysisService` → `StockScoringService`
- [ ] Move `kitecon.analysis.*` → `copilot.prompt.*` (it's AI prompt building, not analysis)
- [ ] Merge `algo.service.ChartAnalysisService` into the new `ChartDataService` + `IndicatorComputeService` from Phase 1

---

## 6. Phase 3 — Clean Package Boundaries

### 6.1 Break Up `kitecon` (369 files → distributed)

The `kitecon` package currently contains 10+ distinct domains. Redistribute:

| Current Location | Target Package | Files |
|-----------------|---------------|-------|
| `kitecon.service.copilot.*` | `copilot.skill/orchestrator/investigation` | ~30 |
| `kitecon.service.ai.*` | `copilot.ai.*` | ~10 |
| `kitecon.web.copilot.*` | `web.copilot.*` | ~8 |
| `kitecon.trade.*` | `trade.*` | ~43 |
| `kitecon.screener.elliott.*` | `screener.elliott.*` | ~27 |
| `kitecon.patternscanner.*` | `screener.pattern.*` | ~15 |
| `kitecon.analysis.*` | `copilot.prompt.*` | ~12 |
| `kitecon.backtest.*` | `strategy.backtest.*` | ~6 |
| `kitecon.simulation.*` | `strategy.simulation.*` | ~9 |
| `kitecon.market.*` | `broker.*` | ~13 |
| `kitecon.auth.*` | `auth.*` | ~12 |
| `kitecon.data.*` | `core.model.*` + respective domain packages | ~30 |
| `kitecon.repository.*` | `core.repository.*` + respective domain packages | ~30 |
| `kitecon.web.*` (non-copilot) | `web.*` | ~12 |
| `kitecon.scan.*` | `screener.ondemand.*` | ~18 |
| `kitecon.watchlist.*` | `market.watchlist.*` | ~7 |
| `kitecon.controller.*` | `web.*` | ~9 |
| `kitecon.config.*` | `config.*` | ~5 |
| `kitecon.service.*` (remaining) | `market.*` / `analysis.*` | ~20 |

**After this, `kitecon` package is deleted.** What remains is Kite-specific broker integration code under `broker.zerodha`.

### 6.2 Absorb Small Standalone Packages

| Current | Target | Rationale |
|---------|--------|-----------|
| `drawings/` (5 files) | `chart.drawing.*` | Drawing is a chart concern |
| `chartdata/` (3 files) | `chart.data.*` | Same |
| `chartpattern/` (17 files) | `ta.pattern.*` | Chart patterns belong in TA library |
| `trade/` (9 files) | `trade.model.*` / `broker.api.*` | Generic trade abstractions |
| `swagger/` (2 files) | `config.swagger.*` | Infrastructure config |

### 6.3 Clean Up `algo` Package

After extracting screener, strategy, and chart services:

| Current | Target |
|---------|--------|
| `algo.screener.*` | `screener.dsl.*` |
| `algo.strategy.*` | `strategy.*` |
| `algo.service.*` (chart/analysis) | `analysis.chart.*` |
| `algo.service.AlertQueueService` | `alert.queue.*` |
| `algo.controller.*` | `web.chart.*` |
| `algo.config.*` | `config.*` |
| `algo.runner.*` | `strategy.runner.*` |
| `algo.backtest.*` | `strategy.backtest.*` |

After this, `algo` becomes empty and is deleted.

---

## 7. Phase 4 — Introduce Missing Abstractions

### 7.1 Broker Abstraction Layer

**Problem**: Zerodha and Dhan integrations have separate facade hierarchies with no shared interface.

```java
// broker/api/BrokerFacade.java
public interface BrokerFacade {
    BrokerType getType();
    HistoricalData fetchHistorical(HistoricalDataRequest request);
    Quote getQuote(String tradingSymbol);
    OrderResult placeOrder(OrderRequest request);
    OrderResult modifyOrder(String orderId, OrderModification mod);
    void cancelOrder(String orderId);
    List<Position> positions();
    List<Holding> holdings();
}

// broker/api/BrokerFacadeProvider.java
@Service
public class BrokerFacadeProvider {
    private final Map<BrokerType, BrokerFacade> facades;
    public BrokerFacade get(BrokerType type) { ... }
    public BrokerFacade getDefault() { ... }  // from config
}
```

- [ ] Extract common interface from `ZerodhaMarketFacade` + `DhanFacade`
- [ ] Implement `ZerodhaBrokerFacade implements BrokerFacade`
- [ ] Implement `DhanBrokerFacade implements BrokerFacade`
- [ ] `TradeEntryHandler`, `TradeExitHandler` use `BrokerFacadeProvider` instead of direct Zerodha calls

### 7.2 Domain Event Bus

**Problem**: Services call each other directly for cross-cutting concerns (trade placed → alert sent → log written → monitoring started).

```java
// core/event/DomainEvent.java
public interface DomainEvent {
    Instant occurredAt();
}

// core/event/DomainEventPublisher.java
@Component
public class DomainEventPublisher {
    private final ApplicationEventPublisher publisher;
    public void publish(DomainEvent event) { publisher.publishEvent(event); }
}

// Example events:
// TradeEnteredEvent, TradeExitedEvent, ScreenerCompletedEvent, AlertTriggeredEvent
```

Use Spring's `@EventListener` / `@TransactionalEventListener` — no external message broker needed.

**Benefits**:
- `TradeEntryHandler` publishes `TradeEnteredEvent`
- `AlertQueueService` listens for `TradeEnteredEvent` → sends WhatsApp
- `TradeActionLogger` listens for `TradeEnteredEvent` → logs to DB
- `TradeMonitorWorker` listens for `TradeEnteredEvent` → starts monitoring
- No circular dependency between any of these services

### 7.3 Alert Channel Abstraction

```java
public interface AlertChannel {
    void send(Alert alert);
    boolean supports(AlertType type);
    String getChannelName();
}

@Component
public class WhatsAppChannel implements AlertChannel { ... }

@Component
public class WebSocketChannel implements AlertChannel { ... }

// Future: EmailChannel, TelegramChannel
```

### 7.4 Result/Error Handling Pattern

Introduce a consistent `Result<T>` type for service methods that can fail:

```java
public sealed interface Result<T> {
    record Success<T>(T value) implements Result<T> {}
    record Failure<T>(String code, String message, Exception cause) implements Result<T> {}
}
```

Adopt for: order placement, screener execution, AI provider calls — anywhere that currently swallows exceptions or returns null.

---

## 8. Phase 5 — Infrastructure Hardening

### 8.1 Database Migrations (Replace ddl-auto=update)

**Current**: `spring.jpa.hibernate.ddl-auto=update` — Hibernate guesses schema changes. Dangerous in production.

- [ ] Add Flyway dependency to `build.gradle`
- [ ] Generate baseline migration from current production schema: `V1__baseline.sql`
- [ ] Set `spring.jpa.hibernate.ddl-auto=validate` (Hibernate only validates, never modifies)
- [ ] All future schema changes go through `V2__xxx.sql`, `V3__xxx.sql`, etc.

### 8.2 Fix Dockerfile (Java 11 → 21)

**Current**: `FROM gradle:jdk11` but project uses Java 21.

- [ ] Update to `FROM eclipse-temurin:21-jdk` (build) + `FROM eclipse-temurin:21-jre` (runtime)
- [ ] Multi-stage build: build stage compiles, runtime stage runs the jar
- [ ] Remove `git` and `wget` from runtime image

### 8.3 Configuration Cleanup

**Current**: `application.properties` has 200+ lines mixing concerns.

- [ ] Split into profile-specific files:
  - `application.yml` — shared defaults
  - `application-local.yml` — local dev overrides
  - `application-prod.yml` — production overrides
  - `application-test.yml` — test overrides (replace current test properties)
- [ ] Move all secrets to environment variables (remove defaults from properties)
- [ ] Migrate DB-backed configs to use `@ConfigurationProperties` beans

### 8.4 Test Infrastructure

**Current**: 45 test classes, mostly integration tests hitting real DB.

- [ ] Adopt Testcontainers for MySQL (replace H2 mock + real DB split)
- [ ] Add `@DataJpaTest` sliced tests for repositories
- [ ] Add unit tests for each newly extracted service class (target: each Phase 1 split gets tests)
- [ ] Add contract tests for `BrokerFacade` implementations

---

## 9. Dependency Map (Before & After)

### Before (Simplified)
```
                        ┌──────────────┐
                        │   kitecon    │ (369 files — everything depends on everything)
                        └──────┬───────┘
                   ┌───────────┼───────────┐
                   │           │           │
              ┌────▼───┐  ┌───▼────┐  ┌───▼──────┐
              │  algo   │  │   ta   │  │ elliott  │
              │ (166)   │  │  (90)  │  │  (40)    │
              └────┬────┘  └───┬────┘  └──────────┘
                   │           │
              ┌────▼───────────▼────┐
              │ chartpattern (17)   │
              └─────────────────────┘
```

**Problem**: `kitecon` has bidirectional dependencies with `algo`. Both depend on `ta`. `elliott.advanced` is an orphaned satellite.

### After
```
                    ┌──────────────┐
                    │    config    │ ← Global config, security, async
                    └──────┬───────┘
                           │
              ┌────────────▼────────────┐
              │         core            │ ← Candle, Instrument, enums, events
              └────────────┬────────────┘
                           │
         ┌─────────┬───────┼───────┬──────────┐
         │         │       │       │          │
    ┌────▼──┐ ┌───▼───┐ ┌─▼──┐ ┌──▼───┐ ┌───▼────┐
    │broker │ │market │ │ ta │ │alert │ │ auth   │
    └───┬───┘ └───┬───┘ └─┬──┘ └──────┘ └────────┘
        │         │       │
        └────┬────┘   ┌───┘
             │        │
        ┌────▼────────▼────┐
        │    screener      │ ← Unified, uses ta + market
        ├──────────────────┤
        │    strategy      │ ← Unified, uses ta + market + broker
        ├──────────────────┤
        │    trade         │ ← Uses broker + alert + strategy
        ├──────────────────┤
        │    analysis      │ ← Uses ta + market
        └────────┬─────────┘
                 │
        ┌────────▼─────────┐
        │    copilot       │ ← Uses analysis + trade + screener
        └────────┬─────────┘
                 │
        ┌────────▼─────────┐
        │      web         │ ← Thin controllers, uses all services
        └──────────────────┘
```

**Dependency rule**: arrows point DOWN only. No upward or circular dependencies.

---

## 10. Risk Register & Rollback Strategy

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Import breakage after mass package moves | High | Low | IDE refactor + compile check per phase |
| Runtime Spring bean wiring failures | Medium | Medium | Integration test suite after each phase; `@ComponentScan` updates |
| Frontend API URL breakage | Low | High | Controllers keep same `@RequestMapping` paths; only package moves |
| Submodule symlink breakage | Medium | Medium | Run `strategies/setup.sh` + compile after strategy package moves |
| Production deployment during refactor | Low | Critical | Feature-branch per phase; merge only after full CI pass |
| Test breakage from package renames | High | Low | IDE handles import updates; verify with `./gradlew test` |

**Rollback**: Each phase is a separate PR. If a phase breaks production, revert the single PR. No phase depends on another being deployed — they can ship independently.

---

## 11. Execution Order & Priority Matrix

### Priority by Value

| Phase | Effort | Value | Risk | Priority |
|-------|--------|-------|------|----------|
| Phase 1 — Break Monoliths | Low (class-level splits, no moves) | High (testability, readability) | Low | **P0 — Do First** |
| Phase 2 — Unify Screeners | Medium | High (feature velocity) | Medium | **P1** |
| Phase 2 — Unify Elliott | Medium | Medium (reduces confusion) | Low | **P1** |
| Phase 2 — Unify Strategy | Medium | High (single execution path) | Medium | **P1** |
| Phase 2 — Merge Analysis | Low | Medium (clarity) | Low | **P1** |
| Phase 3 — Break kitecon | High (mass moves) | High (navigability) | Medium | **P2** |
| Phase 4 — Broker Abstraction | Medium | High (multi-broker) | Low | **P2** |
| Phase 4 — Event Bus | Medium | High (decoupling) | Low | **P2** |
| Phase 5 — Flyway | Low | High (production safety) | Low | **P1** |
| Phase 5 — Fix Dockerfile | Low | Medium (correctness) | Low | **P1** |
| Phase 5 — Config Cleanup | Low | Medium (clarity) | Low | **P3** |
| Phase 5 — Test Infra | Medium | Medium (confidence) | Low | **P3** |

### Recommended Execution Order

```
Sprint 1:  Phase 1 (all monolith splits) + Phase 5.1 (Flyway) + Phase 5.2 (Dockerfile)
Sprint 2:  Phase 2.1 (Unified Screener interface) + Phase 2.2 (Elliott merge)
Sprint 3:  Phase 2.3 (Strategy merge) + Phase 2.4 (Analysis merge)
Sprint 4:  Phase 4.1 (Broker abstraction) + Phase 4.2 (Event bus)
Sprint 5:  Phase 3 (Break up kitecon — the big move)
Sprint 6:  Phase 3 continued (Break up algo) + Phase 4.3-4.4 (Alert channel, Result type)
Sprint 7:  Phase 5.3-5.4 (Config cleanup, Test infra)
```

### Definition of Done per Phase

- [ ] `./gradlew compileJava` passes
- [ ] `./gradlew test` passes (all existing tests)
- [ ] No class exceeds 600 LOC (Phase 1+)
- [ ] No circular package dependencies (Phase 3+)
- [ ] All REST endpoints return same responses (no API breaking changes)
- [ ] Submodule symlinks work (`strategies/setup.sh` + compile)
- [ ] PR reviewed and merged

---

## Appendix A — Files to Touch Per Phase

### Phase 1 (Monolith Splits)
- `kitecon/service/copilot/CopilotSkillService.java` → split into 3
- `algo/service/TradingViewChartService.java` → split into 3
- `kitecon/web/copilot/CopilotAnalysisController.java` → extract facade
- `algo/service/AlertQueueService.java` → extract WhatsApp sender
- `kitecon/service/ChartSnapshotService.java` → extract search

### Phase 2 (Unifications)
- 98 screener files → unified under `screener/`
- 92 Elliott files → merged under `ta/elliott/`
- 54 strategy files → merged under `strategy/`
- 4 analysis services → merged into 3 clear services

### Phase 3 (Package Moves)
- ~369 files move out of `kitecon/`
- ~166 files move out of `algo/`
- Both packages deleted

### Phase 4 (New Abstractions)
- ~15 new interface/class files
- ~30 files updated to use new interfaces

### Phase 5 (Infrastructure)
- `build.gradle` — add Flyway, Testcontainers
- `Dockerfile` — rewrite for Java 21
- `application.properties` → split into YAML profiles
- ~10 new migration SQL files
- ~20 new test classes

---

*Generated from graphify analysis (5307 nodes, 13598 edges, 235 communities) and full codebase exploration.*

---

## 12. Granular Task Breakdown (2-3 Day Tasks)

**Current test coverage**: 42 test classes, 184 tests, ~81% of services have ZERO tests.  
**Approach**: Safety-net tests FIRST → Refactor → Post-refactor tests where needed.

Each task is scoped to 2-3 days of focused work. Tasks are grouped into **Sprints** that align with the phases above.

---

### SPRINT 0 — Safety-Net Tests (Pre-Refactor)

The goal is NOT full coverage — it's capturing the existing behaviour of classes we're about to split/move so that regressions are caught immediately.

---

#### T0.1 — Trade Execution Safety Net (3 days)

**Why first**: Trade execution is the most financially critical path. Any regression = real money lost.

**Pre-work**: Read `.local-dev-credentials`, ensure local MySQL has test data, verify `./gradlew test` passes.

**Tests to write**:

1. **`TradeEntryHandlerTest`** (unit, ~8 tests)
   - Entry triggered when LTP crosses entry price (LONG: LTP >= entry, SHORT: LTP <= entry)
   - Entry NOT triggered when price hasn't crossed
   - Entry skipped when entry window expired (`entryValidUntil < now`)
   - Entry skipped when ML filter score < threshold (0.82)
   - Entry skipped when signal status != WATCHING_ENTRY
   - Dry-run mode: no orders created, only logs
   - TradeActionLog created on every handle() call
   - Mock: `BrokerOrderService`, `TradeOrchestrationService`, `PatternScanService`, `TradeActionLogger`

2. **`TradeExitHandlerTest`** (unit, ~8 tests)
   - Exit triggered when SL hit (LONG: LTP <= SL, SHORT: LTP >= SL)
   - Exit triggered when target hit (LONG: LTP >= target, SHORT: LTP <= target)
   - Exit via ExitStrategy for IMPULSE type (delegates to ExitStrategyRouter)
   - Unrealised P&L formula correct: LONG = (ltp - entry) * qty, SHORT = (entry - ltp) * qty
   - No exit when signal status != ACTIVE
   - Dry-run mode: no exit executed
   - LTP fetch failure: skip tick, no crash
   - Mock: `BrokerOrderService`, `TradeOrchestrationService`, `ExitStrategyRouter`, `TradeActionLogger`

3. **`TradeOrchestrationServiceTest`** (unit, ~6 tests)
   - `onEntryTriggered`: creates TradeOrder for each enabled SegmentConfig
   - Entry price = Ask for LONG, Bid for SHORT
   - Options direction always LONG regardless of signal direction
   - `onExitTriggered`: exits all open orders for signal
   - Exit price = Bid for LONG, Ask for SHORT
   - P&L calculation: LONG = (exit - entry) * qty, SHORT = (entry - exit) * qty
   - Mock: `MarketQuoteService`, `InstrumentResolverService`, `PaperOrderExecutionService`, `SegmentConfigRepository`

4. **`PaperOrderExecutionServiceTest`** — already has 5 tests, verify they cover entry AND exit paths. Add if missing:
   - `enter()` creates order with correct status, price, quantity
   - `exit()` sets exitPrice, exitTime, calculates pnl, sets status CLOSED

**Done when**: `./gradlew test --tests "*TradeEntryHandler*" --tests "*TradeExitHandler*" --tests "*TradeOrchestration*"` → all green.

---

#### T0.2 — Screener Execution Safety Net (3 days)

**Why**: Three screener systems are being unified (Phase 2.1). Must capture current behaviour of each before merging.

**Tests to write**:

1. **`ScreenerServiceTest`** (unit, ~6 tests) — DSL screener
   - `run()` loads screener entity from DB, compiles Kotlin script
   - `run()` caches compiled script on second call (not re-compiled)
   - `run()` with dirty flag forces recompilation
   - `run()` builds ScreenerContext with correct symbol, timeframe, barSeries
   - `run()` chains UOWs: Script → OpenAI (when workflow includes OPENAI)
   - `run()` only Script UOW when workflow excludes OPENAI
   - Mock: `ScreenerRepository`, `KotlinScriptExecutor`, `ScreenerContextLoader`

2. **`ElliottScreenerServiceTest`** (unit, ~6 tests) — Elliott screener
   - `runScreener()` iterates all symbols in screener config
   - Per-symbol: calls `ElliottSymbolScanService.scanSymbol()`
   - On PASSED status: creates `ElliottTradeSuggestion` with entry/SL/target
   - On ERROR status: persists `ElliottScreenerRunResult` with error message
   - Run history: `ElliottScreenerRun` entity persisted with start/end time
   - Respects screener enabled/disabled flag
   - Mock: `ElliottSymbolScanService`, `ElliottScreenerRepository`, `ElliottScreenerRunRepository`

3. **`PatternScanServiceTest`** (unit, ~6 tests) — Pattern screener
   - `scan()` refreshes candle data for all 3 timeframes (watching, confirm, parent)
   - `scan()` detects ZigZag pivots from bar series
   - `scan()` runs all 5 pattern scanners (DTB, Triangle, HNS, Flag, TLB)
   - `scan()` filters to only recent patterns (last pivot == latest pivot)
   - `scan()` returns indicator values (ATR, RSI, MACD, StochRSI) at each pivot
   - Empty bar series → returns empty result (not exception)
   - Mock: `DataFetchService`, `ZigZagService`, `PatternComboBacktestService`

**Done when**: All 3 screener types have isolated unit tests covering the happy path and key edge cases.

---

#### T0.3 — Copilot AI Orchestration Safety Net (2 days)

**Why**: CopilotSkillService (2766 LOC) is being split into 3 classes. Must capture its current API contract.

**Tests to write**:

1. **`CopilotSkillServiceTest`** (unit, ~8 tests)
   - CRUD: create, read, update, delete skill
   - `getAllSkillsForUser()`: returns only active skills for user
   - `buildPrompt()`: produces prompt with indicator section, pivot section, context
   - `buildScanPrompt()`: produces shorter scan-specific prompt
   - `formatIndicatorSection()`: formats RSI/MACD/ADX values correctly
   - `formatPivotSection()`: includes ZigZag pivots with price/time
   - Skill with no indicators → empty indicator section (not null/exception)
   - Skill with no pivots → empty pivot section
   - Mock: `CopilotSkillRepository`, any data dependencies

2. **`CopilotOrchestratorServiceTest`** (unit, ~5 tests)
   - Orchestration loop stops at `analysisComplete=true`
   - Orchestration loop stops at MAX_TURNS=20
   - Cycle detection: skill already invoked → not re-invoked
   - Custom instructions loaded from DB per user (fallback to hardcoded)
   - `validateInstructions()` returns valid=true for well-formed instructions
   - Mock: `CopilotAIService`, `CopilotSkillService`, `CopilotOrchestratorConfigRepository`

3. **`AIResponseParserTest`** (unit, ~6 tests)
   - Parses FINDING response → AIResponse.type == FINDING
   - Parses ENTRY_SIGNAL response → extracts entry/SL/target
   - Parses ORCHESTRATOR response → extracts skillsToInvoke[]
   - Parses INVALIDATED response → extracts invalidation reason
   - Parses FLAG_ANOMALY → extracts flag type and description
   - Malformed JSON → returns NEEDS_EXPERT (graceful degradation)

**Done when**: Skill CRUD, prompt building, orchestration loop, and response parsing all have isolated unit tests.

---

#### T0.4 — Chart & Alert Service Safety Net (2 days)

**Why**: TradingViewChartService (1398 LOC) and AlertQueueService (438 LOC) are being split. ChartSnapshotService (457 LOC) is being split.

**Tests to write**:

1. **`TradingViewChartServiceTest`** (unit, ~8 tests)
   - Returns correct OHLCV data for given symbol/interval
   - Computes SMA correctly (verify against known values)
   - Computes EMA correctly
   - Computes RSI correctly
   - Computes MACD correctly (signal, histogram)
   - Computes Bollinger Bands correctly (upper, middle, lower)
   - Formats output as TradingView-compatible JSON
   - Empty bar series → returns empty response (not exception)
   - Mock: `BarSeriesLoader`, `CandleRepository`

2. **`AlertQueueServiceTest`** (unit, ~6 tests)
   - `addAlert()` enqueues alert and returns true
   - `addAlert()` with duplicate key → returns false (dedup works)
   - `processAlerts()` processes all queued alerts for given type
   - `clearQueue()` removes all alerts for type
   - `getAllStatistics()` returns correct counts per AlertType
   - `deriveFamilyTimeframes()` returns child + base + 2 parents
   - Mock: `ChartAnalysisService`

3. **`ChartSnapshotServiceTest`** (unit, ~5 tests)
   - Create snapshot → persisted with correct user, symbol, timeframe
   - Get snapshot by ID → returns correct data
   - Search by tag → returns matching snapshots
   - Search by symbol → returns matching snapshots
   - Delete snapshot → only allowed by owner
   - Mock: `ChartSnapshotRepository`

**Done when**: All 3 services have unit tests capturing their current API contract.

---

#### T0.5 — Strategy & Simulation Safety Net (2 days)

**Why**: Two strategy frameworks are being unified (Phase 2.3). Must capture current behaviour.

**Tests to write**:

1. **`BackTestingHandlerJsonTest`** — already has 1 test. Expand:
   - `execute()` loads bar series from config
   - `execute()` builds strategy from StrategyConfig
   - `execute()` returns all TA4J criterion values
   - `execute()` with BUY direction → enters long positions
   - `execute()` with SELL direction → enters short positions
   - Empty bar series → returns empty result
   - Mock: `RdbmsBarSeriesLoader`, `StrategyBuilder`

2. **`TradeSimulationServiceTest`** (unit, ~5 tests)
   - Simulation runs through all bars in series
   - Entry signal at correct bar based on strategy rules
   - Exit at SL/target/end-of-series
   - P&L aggregation across multiple trades
   - Simulation clock advances correctly per bar
   - Mock: `BarSeriesLoader`, `SimulationStrategy`

3. **`FinalStrategyBuilderTest`** (unit, ~4 tests)
   - Builds strategy from StrategyConfig with entry+exit rules
   - Caches indicator instances (not re-created per rule)
   - Handles missing optional indicators gracefully
   - Mock: `CachedIndicatorBuilder`, `CachedRuleBuilder`

**Done when**: Backtest handler, simulation service, and strategy builder have isolated unit tests.

---

#### T0.6 — Market Data & Broker Safety Net (2 days)

**Why**: MarketFacade and DataProvider are being extracted into broker abstraction (Phase 4.1).

**Tests to write**:

1. **`MarketDataProviderServiceTest`** (unit, ~4 tests)
   - `getBarSeries()` with provider=database → delegates to `DatabaseMarketDataProvider`
   - `getBarSeries()` with provider=zerodha → delegates to `ZerodhaMarketDataProvider`
   - Provider selection based on `market.data.provider` property
   - Fallback when primary provider fails
   - Mock: `DatabaseMarketDataProvider`, `ZerodhaMarketDataProvider`

2. **`ZerodhaMarketFacadeTest`** (unit, ~4 tests)
   - `fetchHistorical()` calls Kite API with correct params
   - `getQuote()` returns Ask/Bid/Last prices
   - `placeOrder()` sends correct order params to Kite
   - API timeout → throws MarketException (not swallowed)
   - Mock: `KiteConnect` (from KiteConnectPool)

3. **`LatestBarSeriesProviderTest`** — already has 1 test. Expand:
   - `loadBarSeries()` caches result on second call
   - `updateBarSeries()` with new bar time → returns completed bar
   - `updateBarSeries()` with same bar time → returns null, updates OHLCV
   - Bar end time calculation for 15min, 1h, 1d intervals

**Done when**: Market data flow (fetch → cache → serve) has full unit coverage.

---

### SPRINT 0 SUMMARY

| Task | Days | Tests | Critical Path Protected |
|------|------|-------|------------------------|
| T0.1 Trade Execution | 3 | ~22 | Entry → Order → Exit → P&L |
| T0.2 Screener Execution | 3 | ~18 | DSL, Elliott, Pattern scan flows |
| T0.3 Copilot AI | 2 | ~19 | Skill CRUD, orchestration loop, response parsing |
| T0.4 Chart & Alert | 2 | ~19 | Chart indicators, alert queue, snapshots |
| T0.5 Strategy & Simulation | 2 | ~14 | Backtest, simulation, strategy building |
| T0.6 Market Data & Broker | 2 | ~12 | Data fetch, market facade, bar series cache |
| **TOTAL** | **14 days** | **~104 new tests** | **All 6 critical runtime paths** |

After Sprint 0: test count goes from 184 → ~288, and every class being refactored has at least its public API under test.

---

### SPRINT 1 — Break Monoliths + Infrastructure Quick Wins

---

#### T1.1 — Split CopilotSkillService into 3 Classes (3 days)

**Pre-check**: T0.3 tests pass (`CopilotSkillServiceTest`).

**Day 1 — Extract SkillPromptBuilder**:
- Create `SkillPromptBuilder.java` in same package (`kitecon.service.copilot`)
- Move methods: `buildPrompt()`, `buildFullAnalysisPrompt()`, `formatIndicatorSection()`, `formatPivotSection()`, `formatVolumeSection()`, `formatTrendSection()`, all `buildXxxSection()` helpers
- `SkillPromptBuilder` constructor takes `CopilotSkillCrudService` (for loading skill definitions)
- Verify: T0.3 tests still pass (may need import updates)

**Day 2 — Extract ScanPromptBuilder**:
- Create `ScanPromptBuilder.java`
- Move methods: `buildScanPrompt()`, `buildQuickScanPrompt()`, `buildScanContext()`, all scan-specific formatting
- `ScanPromptBuilder` takes `CopilotSkillCrudService` + shared formatting utils
- Rename original `CopilotSkillService` → `CopilotSkillCrudService`
- Update all injection points: `CopilotSkillController`, `CopilotOrchestratorService`, `CopilotAnalysisController`

**Day 3 — Wire & Verify**:
- Update `CopilotSkillController` to inject `SkillPromptBuilder` for prompt endpoints
- Update `CopilotAnalysisController` to inject `ScanPromptBuilder` for scan endpoints
- Run full test suite: `./gradlew test`
- Verify no class exceeds 600 LOC
- Manual smoke test: trigger a copilot analysis on local, verify JSON response shape is identical

**Post-refactor tests** (included in day 3):
- `SkillPromptBuilderTest`: 3 tests — prompt includes indicators, pivots, context sections
- `ScanPromptBuilderTest`: 2 tests — scan prompt is shorter, includes correct sections

**Done when**: 3 classes exist, all <600 LOC, full test suite green, copilot analysis returns same JSON shape.

---

#### T1.2 — Split TradingViewChartService into 3 Classes (3 days)

**Pre-check**: T0.4 tests pass (`TradingViewChartServiceTest`).

**Day 1 — Extract ChartDataService**:
- Create `ChartDataService.java` in `algo.service`
- Move methods: all bar series loading, candle fetching, data assembly logic
- `ChartDataService` depends on `BarSeriesLoader`, `CandleRepository`

**Day 2 — Extract IndicatorComputeService**:
- Create `IndicatorComputeService.java`
- Move methods: `computeSMA()`, `computeEMA()`, `computeRSI()`, `computeMACD()`, `computeBollinger()`, all indicator calculation methods
- `IndicatorComputeService` takes a BarSeries, returns Map<String, List<Number>>
- Rename original → `TradingViewFormatter` (only JSON formatting remains)

**Day 3 — Wire & Verify**:
- Controller chain: `ChartController` → `TradingViewFormatter` → `IndicatorComputeService` → `ChartDataService`
- Run full test suite
- Manual smoke test: open TradingView chart locally, verify indicators render correctly

**Post-refactor tests**:
- `ChartDataServiceTest`: 2 tests — loads bar series for symbol/interval
- `IndicatorComputeServiceTest`: 3 tests — SMA/RSI/MACD output matches known values

**Done when**: 3 classes, all <500 LOC, indicators render correctly on local chart.

---

#### T1.3 — Slim Down CopilotAnalysisController + Extract Facade (2 days)

**Pre-check**: T0.3 tests pass.

**Day 1 — Extract CopilotAnalysisFacade**:
- Create `CopilotAnalysisFacade.java` in `kitecon.service.copilot`
- Move all business logic from controller: scan phase orchestration, reason phase orchestration, response assembly, error wrapping
- Controller methods become:
  ```java
  @PostMapping("/trigger")
  public ResponseEntity<?> triggerAnalysis(Authentication auth, @RequestBody Map body) {
      Long userId = extractUserId(auth);
      return ResponseEntity.ok(facade.triggerAnalysis(userId, body));
  }
  ```

**Day 2 — Wire, Test & Verify**:
- Update all controller endpoints to delegate to facade
- Controller should be <200 LOC
- Run test suite
- `CopilotAnalysisFacadeTest`: 4 tests — scan phase returns observations, reason phase creates hypotheses, max turns respected, error wrapped in response

**Done when**: Controller <200 LOC, facade has unit tests, same API responses.

---

#### T1.4 — Split AlertQueueService + ChartSnapshotService (2 days)

**Pre-check**: T0.4 tests pass.

**Day 1 — AlertQueueService split**:
- Extract `WhatsAppAlertSender.java` implementing new `AlertChannel` interface
- `AlertChannel` interface: `void send(Alert alert)`, `boolean supports(AlertType type)`
- `AlertQueueService` uses `List<AlertChannel>` (Spring auto-collects implementations)
- Keeps queue management, dedup, rate limiting
- Tests: `WhatsAppAlertSenderTest` — 2 tests (sends correct HTTP payload, respects rate limit)

**Day 2 — ChartSnapshotService split**:
- Extract `SnapshotSearchService.java` — all search/filter/tag-query methods
- `ChartSnapshotService` keeps CRUD + permission checks
- Tests: `SnapshotSearchServiceTest` — 3 tests (search by tag, by symbol, by date range)
- Run full suite

**Done when**: 4 new classes, each <300 LOC, all tests green.

---

#### T1.5 — Add Flyway Database Migrations (2 days)

**Why now**: Before any schema-touching refactor, lock down the schema.

**Day 1 — Baseline migration**:
- Add Flyway to `build.gradle`: `implementation 'org.flywaydb:flyway-core'`, `implementation 'org.flywaydb:flyway-mysql'`
- Connect to production DB, export full schema: `mysqldump --no-data algotrading > V1__baseline.sql`
- Place in `src/main/resources/db/migration/V1__baseline.sql`
- Add `spring.flyway.baseline-on-migrate=true` to properties (so existing DB isn't wiped)
- Change `spring.jpa.hibernate.ddl-auto=validate`

**Day 2 — Test & fix**:
- Run `./gradlew bootRun` locally — verify Flyway runs baseline, Hibernate validates
- Fix any schema mismatches between entities and actual DB
- Update test properties: `spring.flyway.enabled=false` for H2 tests (or provide H2-compatible baseline)
- Run full test suite
- Document migration workflow in CLAUDE.md

**Done when**: `ddl-auto=validate`, Flyway manages schema, all tests green.

---

#### T1.6 — Fix Dockerfile for Java 21 (1 day)

**Day 1**:
- Rewrite Dockerfile as multi-stage:
  ```dockerfile
  FROM eclipse-temurin:21-jdk AS build
  COPY . /app
  WORKDIR /app
  RUN ./gradlew bootJar --no-daemon

  FROM eclipse-temurin:21-jre
  COPY --from=build /app/build/libs/*.jar /app/app.jar
  EXPOSE 8080
  ENTRYPOINT ["java", "-Xms4g", "-Xmx6g", "-jar", "/app/app.jar"]
  ```
- Build locally: `docker build -t algo-trade .`
- Run locally: `docker run -p 8080:8080 algo-trade` — verify healthcheck
- Update `start.sh` if needed

**Done when**: Docker builds with Java 21, container starts and responds to `/actuator/health`.

---

### SPRINT 1 SUMMARY

| Task | Days | What Changes |
|------|------|-------------|
| T1.1 Split CopilotSkillService | 3 | 1 class → 3 classes |
| T1.2 Split TradingViewChartService | 3 | 1 class → 3 classes |
| T1.3 Slim CopilotAnalysisController | 2 | Extract facade, controller <200 LOC |
| T1.4 Split Alert + Snapshot | 2 | 2 classes → 4 classes + AlertChannel interface |
| T1.5 Flyway migrations | 2 | ddl-auto=update → validate + Flyway |
| T1.6 Fix Dockerfile | 1 | Java 11 → 21 |
| **TOTAL** | **13 days** | Monoliths broken, schema locked, Docker fixed |

---

### SPRINT 2 — Unified Screener Interface + Elliott Merge

---

#### T2.1 — Design & Implement Screener<T> Interface (2 days)

**Day 1 — Interface + base classes**:
- Create `com.dtech.algo.screener.api` package (temporary location, moved in Phase 3)
- Define:
  ```java
  public interface Screener<T extends ScreenerResult> {
      String getName();
      ScreenerType getType();
      List<T> scan(List<Instrument> instruments, ScreenerConfig config);
  }
  public interface ScreenerResult {
      String getSymbol();
      Instant getTimestamp();
      double getConfidence();
      Map<String, Object> getMetadata();
  }
  public enum ScreenerType { DSL, ELLIOTT, PATTERN }
  ```
- Create `ScreenerRegistry` — Spring-injected `Map<ScreenerType, Screener<?>>`

**Day 2 — Tests + verification**:
- `ScreenerRegistryTest`: 3 tests — registers all 3 types, lookup by type works, unknown type → empty
- Verify existing screener code compiles (no implementations yet — just the interface)

**Done when**: Interface exists, registry works, compiles clean.

---

#### T2.2 — Adapt DSL Screener to Screener<T> (2 days)

**Day 1 — Implement adapter**:
- Create `DslScreenerAdapter implements Screener<DslScreenerResult>`
- `DslScreenerResult implements ScreenerResult`
- Adapter wraps existing `ScreenerService.run()` — no logic changes, just interface conformance
- Wire into `ScreenerRegistry`

**Day 2 — Test adapter**:
- `DslScreenerAdapterTest`: 4 tests — getName/getType correct, scan delegates to ScreenerService, results conform to ScreenerResult shape
- Existing `ScreenerServiceTest` (T0.2) still passes
- Old `ScreenerController` still works (no endpoint changes yet)

**Done when**: DSL screener accessible via both old controller and new `Screener<T>` interface.

---

#### T2.3 — Adapt Elliott Screener to Screener<T> (2 days)

Same pattern as T2.2:
- Create `ElliottScreenerAdapter implements Screener<ElliottScreenerResult>`
- `ElliottScreenerResult implements ScreenerResult`
- Wraps `ElliottScreenerService`
- Wire into `ScreenerRegistry`
- Tests: 4 tests — interface conformance, delegation, result shape
- Old `ElliottScreenerController` still works

---

#### T2.4 — Adapt Pattern Screener to Screener<T> (2 days)

Same pattern:
- Create `PatternScreenerAdapter implements Screener<PatternScreenerResult>`
- Wraps `PatternScanService`
- Wire into `ScreenerRegistry`
- Tests: 4 tests

---

#### T2.5 — Unified Screener Controller + Scheduling (3 days)

**Day 1 — Unified controller**:
- Create `UnifiedScreenerController` with endpoints:
  - `POST /api/screeners/{type}/run` — delegates to `ScreenerRegistry.get(type).scan()`
  - `GET /api/screeners/{type}/results` — queries unified result store
  - `GET /api/screeners` — list all registered screeners
- Old controllers remain (deprecated, not removed)

**Day 2 — Unified ScreenerRunRepository**:
- Create `ScreenerRun` entity: id, screenerType, screenerName, startTime, endTime, status, resultCount
- Create `ScreenerRunResult` entity: runId, symbol, confidence, metadata (JSON), timestamp
- `ScreenerRunRepository` extends JpaRepository
- V2 Flyway migration: `V2__screener_run_tables.sql`

**Day 3 — Unified scheduling**:
- Create `ScreenerSchedulerService` that manages cron schedules for any screener type
- Uses Spring `TaskScheduler` to register/cancel jobs
- Stores schedule config in DB: `ScreenerSchedule` entity (screenerId, type, cronExpression, enabled)
- Tests: `UnifiedScreenerControllerTest` (MockMvc, 4 tests), `ScreenerSchedulerServiceTest` (3 tests)

**Done when**: Single `/api/screeners/{type}/run` endpoint works for all 3 types, unified result store, scheduling.

---

#### T2.6 — Merge Elliott Wave Libraries (3 days)

**Day 1 — Move `elliott.advanced` into `ta.elliott`**:
- Move `com.dtech.elliott.advanced.scenario.filter.*` → `com.dtech.ta.elliott.filter.*`
- Move `com.dtech.elliott.advanced.domain.*` → `com.dtech.ta.elliott.scenario.*`
- Move `com.dtech.elliott.advanced.common.*` → `com.dtech.ta.elliott.model.*`
- IDE refactor: update all imports

**Day 2 — Verify & clean**:
- `./gradlew compileJava` — fix any broken imports
- Run all Elliott-related tests (~88 tests) — must be green
- Delete empty `com.dtech.elliott` package
- Verify `kitecon.screener.elliott` still compiles (it consumes `ta.elliott`)

**Day 3 — Reconcile any duplicate logic**:
- Compare `ta.elliott.swing.*` with any similar code in old `elliott.advanced`
- Remove duplicates, keep the more tested version
- Run Elliott tests again
- Add 2-3 integration tests if gaps found during reconciliation

**Done when**: Single `ta.elliott` package, old `elliott` package deleted, 88+ Elliott tests green.

---

### SPRINT 2 SUMMARY

| Task | Days | What Changes |
|------|------|-------------|
| T2.1 Screener interface | 2 | New `Screener<T>` interface + registry |
| T2.2 DSL adapter | 2 | DSL screener implements interface |
| T2.3 Elliott adapter | 2 | Elliott screener implements interface |
| T2.4 Pattern adapter | 2 | Pattern screener implements interface |
| T2.5 Unified controller | 3 | Single screener endpoint + scheduling |
| T2.6 Elliott merge | 3 | 2 packages → 1, ~90 files affected |
| **TOTAL** | **14 days** | Unified screener framework, merged Elliott |

---

### SPRINT 3 — Unified Strategy + Analysis Merge

---

#### T3.1 — Merge Strategy Frameworks (3 days)

**Day 1 — Move algo.strategy into unified package**:
- Create `com.dtech.strategy` (new top-level)
- Move `algo.strategy.config.*` → `strategy.config.*`
- Move `algo.strategy.builder.*` → `strategy.builder.*`
- Move `algo.strategy.units.*` → `strategy.builder.*`
- IDE refactor imports

**Day 2 — Move kitecon.strategy into unified package**:
- Move `kitecon.strategy.builder.*` → `strategy.runner.*`
- Move `kitecon.strategy.exec.*` → `strategy.runner.*`
- Move `kitecon.strategy.dataloader.*` → `strategy.dataloader.*`
- Move `kitecon.strategy.backtest.*` → `strategy.backtest.*`
- Move `kitecon.strategy.sets.*` → `strategy.config.*`

**Day 3 — Move backtest + simulation, verify**:
- Move `kitecon.backtest.*` → `strategy.backtest.*`
- Move `kitecon.simulation.*` → `strategy.simulation.*`
- Move `algo.runner.*` → `strategy.runner.*`
- `./gradlew compileJava` + `./gradlew test`
- Fix any remaining import issues
- Verify `strategies/setup.sh` symlinks still work (impulse strategy)

**Done when**: Single `com.dtech.strategy` package, old locations deleted, all tests green, symlinks work.

---

#### T3.2 — Merge Analysis Services (2 days)

**Day 1 — Consolidate analysis**:
- Rename `kitecon.service.StockAnalysisService` → `StockScoringService` (in-place)
- Move `kitecon.analysis.*` (12 files) → `kitecon.service.copilot.prompt.*` (it's AI prompt building)
- Update all imports: `CopilotAnalysisController`, `CopilotOrchestratorService`, etc.

**Day 2 — Verify & clean**:
- After Phase 1 split (T1.2), `TradingViewChartService` is already 3 classes: `ChartDataService`, `IndicatorComputeService`, `TradingViewFormatter`
- Merge `algo.service.ChartAnalysisService` into `IndicatorComputeService` (absorb the few unique methods)
- Delete `algo.service.ChartAnalysisService`
- Run full test suite
- Verify chart rendering still works locally

**Done when**: 4 analysis services → 3 focused services (ChartData, IndicatorCompute, StockScoring) + separate AIPromptService namespace.

---

### SPRINT 3 SUMMARY

| Task | Days | What Changes |
|------|------|-------------|
| T3.1 Merge strategy frameworks | 3 | 54 files → unified `com.dtech.strategy` |
| T3.2 Merge analysis services | 2 | 4 services → 3 + prompt namespace |
| **TOTAL** | **5 days** | |

---

### SPRINT 4 — Broker Abstraction + Event Bus

---

#### T4.1 — BrokerFacade Interface + Zerodha Implementation (3 days)

**Day 1 — Design interface**:
- Create `com.dtech.kitecon.market.api` package (temporary, moved in Phase 3)
- Define `BrokerFacade` interface (methods from section 7.1 above)
- Define `BrokerType` enum: `ZERODHA`, `DHAN`
- Define request/response DTOs: `HistoricalDataRequest`, `OrderRequest`, `OrderResult`, `QuoteResult`
- Create `BrokerFacadeProvider` — Spring-injected registry

**Day 2 — Zerodha implementation**:
- Create `ZerodhaBrokerFacade implements BrokerFacade`
- Wraps existing `ZerodhaMarketFacade` + `ZerodhaOrderManager`
- No logic changes — just interface conformance
- Wire into `BrokerFacadeProvider`

**Day 3 — Dhan implementation + tests**:
- Create `DhanBrokerFacade implements BrokerFacade`
- Wraps existing Dhan service layer
- Tests: `ZerodhaBrokerFacadeTest` (4 tests), `DhanBrokerFacadeTest` (4 tests), `BrokerFacadeProviderTest` (3 tests)
- Both facades return same DTO types for same operations

**Done when**: Both brokers accessible via `BrokerFacadeProvider.get(type)`, all tests green.

---

#### T4.2 — Migrate Trade Services to BrokerFacade (2 days)

**Day 1 — Update TradeOrchestrationService**:
- Replace direct `KiteConnectConfig`/`ZerodhaMarketFacade` injection with `BrokerFacadeProvider`
- All `kiteConnect.getHistoricalData()` → `brokerFacade.fetchHistorical()`
- All `kiteConnect.placeOrder()` → `brokerFacade.placeOrder()`
- All `kiteConnect.getQuote()` → `brokerFacade.getQuote()`

**Day 2 — Update remaining callers + verify**:
- `TradeEntryHandler`, `TradeExitHandler`, `BrokerOrderService` → use `BrokerFacadeProvider`
- `DataFetchService` → use `BrokerFacade` for historical data
- Run T0.1 safety-net tests (trade execution) — must still pass
- Run full suite

**Done when**: No direct KiteConnect usage in trade services, all via `BrokerFacade`.

---

#### T4.3 — Domain Event Bus (3 days)

**Day 1 — Event infrastructure**:
- Create `com.dtech.kitecon.event` package
- Define `DomainEvent` interface: `Instant occurredAt()`, `String eventType()`
- Create `DomainEventPublisher` — wraps `ApplicationEventPublisher`
- Define events: `TradeEnteredEvent`, `TradeExitedEvent`, `ScreenerCompletedEvent`, `AlertTriggeredEvent`
- Each event is a record with relevant data fields

**Day 2 — Wire trade execution to events**:
- `TradeOrchestrationService.onEntryTriggered()` → after creating order, publish `TradeEnteredEvent`
- `TradeOrchestrationService.onExitTriggered()` → after closing order, publish `TradeExitedEvent`
- Create `@EventListener` in `TradeActionLogger` — listens for `TradeEnteredEvent` + `TradeExitedEvent`
- Create `@EventListener` in `AlertQueueService` — listens for `TradeEnteredEvent` → queues alert
- Remove direct method calls from orchestration service to logger and alert service

**Day 3 — Wire screener + test**:
- After screener run completes, publish `ScreenerCompletedEvent`
- `AlertQueueService` listens for `ScreenerCompletedEvent` → queues alert if results found
- Tests: `DomainEventPublisherTest` (2 tests), `TradeEventListenerTest` (4 tests — logger receives event, alert receives event, order of listeners doesn't matter, event data is correct)
- Run full test suite — verify no behaviour change

**Done when**: Trade entry/exit triggers events instead of direct calls, screener publishes events, all tests green.

---

### SPRINT 4 SUMMARY

| Task | Days | What Changes |
|------|------|-------------|
| T4.1 BrokerFacade interface | 3 | New interface + 2 implementations |
| T4.2 Migrate to BrokerFacade | 2 | Trade services use abstraction |
| T4.3 Domain event bus | 3 | Direct calls → event-driven |
| **TOTAL** | **8 days** | |

---

### SPRINT 5 — The Big Package Move (Break Up kitecon)

---

#### T5.1 — Extract `trade` Package from kitecon (3 days)

**Day 1 — Move trade entities + repositories**:
- Move `kitecon.trade.entity.*` → `com.dtech.trade.entity.*`
- Move `kitecon.trade.repository.*` → `com.dtech.trade.repository.*`
- Move `kitecon.trade.enums.*` → `com.dtech.trade.enums.*`
- IDE refactor imports

**Day 2 — Move trade services**:
- Move `kitecon.trade.service.*` → `com.dtech.trade.service.*`
- Move `kitecon.trade.strategy.*` → `com.dtech.trade.exit.*`
- Move `kitecon.trade.dto.*` → `com.dtech.trade.dto.*`

**Day 3 — Move controllers + verify**:
- Move `kitecon.trade.controller.*` → `com.dtech.web.trade.*`
- `./gradlew compileJava` + fix imports
- Run T0.1 trade tests — must be green
- Run full suite

---

#### T5.2 — Extract `copilot` Package from kitecon (3 days)

**Day 1 — Move copilot services**:
- Move `kitecon.service.copilot.*` → `com.dtech.copilot.service.*`
- Move `kitecon.service.ai.*` → `com.dtech.copilot.ai.*`
- Move `kitecon.analysis.*` (already renamed to prompt) → `com.dtech.copilot.prompt.*`

**Day 2 — Move copilot data + repos**:
- Move `kitecon.data.copilot.*` → `com.dtech.copilot.entity.*`
- Move `kitecon.repository.copilot.*` → `com.dtech.copilot.repository.*`

**Day 3 — Move controllers + verify**:
- Move `kitecon.web.copilot.*` → `com.dtech.web.copilot.*`
- Move `kitecon.web.settings.*` → `com.dtech.web.settings.*`
- Fix imports, run T0.3 copilot tests, run full suite

---

#### T5.3 — Extract `auth`, `market`, `chart` Packages (3 days)

**Day 1 — Extract auth**:
- Move `kitecon.auth.*` → `com.dtech.auth.*`
- Move security config if it only references auth classes

**Day 2 — Extract market + broker**:
- Move `kitecon.market.*` → `com.dtech.broker.*` (already has BrokerFacade from Sprint 4)
- Move `kitecon.service.dataprovider.*` → `com.dtech.market.provider.*`
- Move `kitecon.service.DataFetchService` → `com.dtech.market.fetch.*`
- Move `kitecon.service.CandleFacade` → `com.dtech.market.candle.*`
- Move `kitecon.watchlist.*` → `com.dtech.market.watchlist.*`

**Day 3 — Extract chart + remaining**:
- Move `kitecon.web.ChartSnapshotController` → `com.dtech.web.chart.*`
- Move `kitecon.web.SnapshotDraftController` → `com.dtech.web.chart.*`
- Move `kitecon.web.ChartStateController` → `com.dtech.web.chart.*`
- Move `kitecon.service.ChartSnapshotService` → `com.dtech.analysis.snapshot.*`
- Move remaining kitecon.web.* → com.dtech.web.*
- Move remaining kitecon.controller.* → com.dtech.web.*

---

#### T5.4 — Extract Screener + Clean Up kitecon (2 days)

**Day 1 — Move screeners**:
- Move `kitecon.screener.elliott.*` → `com.dtech.screener.elliott.*`
- Move `kitecon.patternscanner.*` → `com.dtech.screener.pattern.*`
- Move `kitecon.scan.*` → `com.dtech.screener.ondemand.*`
- Move `algo.screener.*` → `com.dtech.screener.dsl.*`

**Day 2 — Delete kitecon + algo, fix residual**:
- Move remaining files (config, misc, enums, loader, persistence, kite) to appropriate packages
- Move `kitecon.config.*` → `com.dtech.config.*`
- Move `kitecon.data.*` (non-copilot) → `com.dtech.core.model.*`
- Move `kitecon.repository.*` (non-copilot) → `com.dtech.core.repository.*`
- Delete empty `com.dtech.kitecon` and `com.dtech.algo` packages
- Update `@ComponentScan` in main application class if needed
- `./gradlew compileJava` + `./gradlew test` — THE BIG VERIFICATION
- Fix any remaining broken imports

---

#### T5.5 — Absorb Small Packages + Final Verification (2 days)

**Day 1 — Absorb small packages**:
- Move `com.dtech.drawings.*` → `com.dtech.chart.drawing.*`
- Move `com.dtech.chartdata.*` → `com.dtech.chart.data.*`
- Move `com.dtech.chartpattern.*` → `com.dtech.ta.pattern.*`
- Move `com.dtech.trade.*` (old generic) → merge into `com.dtech.trade.model.*`
- Move `com.dtech.swagger.*` → `com.dtech.config.swagger.*`

**Day 2 — Full regression + documentation**:
- `./gradlew clean build` — full clean build
- Run all tests
- Verify all REST endpoints return same responses (hit each controller manually or via `docs/test-endpoints.sh`)
- Update `@ComponentScan` base packages in application class
- Update `strategies/setup.sh` symlinks if source paths changed
- Run `graphify update .` to rebuild knowledge graph with new structure

**Done when**: `kitecon` and `algo` packages no longer exist, all tests green, all endpoints respond correctly.

---

### SPRINT 5 SUMMARY

| Task | Days | What Changes |
|------|------|-------------|
| T5.1 Extract trade | 3 | ~43 files move |
| T5.2 Extract copilot | 3 | ~60 files move |
| T5.3 Extract auth/market/chart | 3 | ~50 files move |
| T5.4 Extract screener + cleanup | 2 | ~80 files move, kitecon/algo deleted |
| T5.5 Absorb small packages | 2 | ~45 files move, final verification |
| **TOTAL** | **13 days** | ~280 files reorganized |

---

### SPRINT 6 — Post-Refactor Tests + Config Cleanup

---

#### T6.1 — Contract Tests for BrokerFacade (2 days)

Write contract tests that both `ZerodhaBrokerFacade` and `DhanBrokerFacade` must pass:
- `fetchHistorical()` returns bars in chronological order
- `getQuote()` returns non-null Ask, Bid, Last
- `placeOrder()` with valid request → returns orderId
- `placeOrder()` with invalid symbol → throws `BrokerException`
- `cancelOrder()` with valid orderId → no exception
- `cancelOrder()` with unknown orderId → throws `BrokerException`

Use abstract test class pattern: `AbstractBrokerFacadeContractTest` with two subclasses.

---

#### T6.2 — Integration Test with Testcontainers (3 days)

**Day 1 — Setup Testcontainers**:
- Add `org.testcontainers:mysql` to build.gradle (testImplementation)
- Create `@TestConfiguration` that spins up MySQL container
- Create `AbstractIntegrationTest` base class with `@Container` + `@DynamicPropertySource`

**Day 2 — Migrate existing integration tests**:
- `TradeLifecycleE2ETest` → use Testcontainers MySQL (not H2 or local MySQL)
- `TradingSystemIntegrationTest` → same
- `CopilotOrchestrationIntegrationTest` → same
- Verify all pass

**Day 3 — Add new integration tests**:
- `ScreenerIntegrationTest`: run DSL screener end-to-end with real DB
- `BacktestIntegrationTest`: run backtest end-to-end with real DB
- `AlertFlowIntegrationTest`: alert creation → event → queue → verify queued

---

#### T6.3 — Configuration Cleanup (2 days)

**Day 1 — Convert to YAML profiles**:
- Convert `application.properties` → `application.yml`
- Extract `application-local.yml` (local dev overrides)
- Extract `application-prod.yml` (production overrides)
- Move secrets to environment variables only (remove default values)

**Day 2 — Add @ConfigurationProperties beans**:
- `KiteProperties` — all kite.* properties
- `ImpulseProperties` — all impulse.* properties
- `AlertProperties` — all alerts.* properties
- `TradeMonitorProperties` — all trade.monitor.* properties
- Replace `@Value` injections with typed property beans
- Run full test suite

---

### SPRINT 6 SUMMARY

| Task | Days | What Changes |
|------|------|-------------|
| T6.1 BrokerFacade contract tests | 2 | ~12 contract tests |
| T6.2 Testcontainers migration | 3 | Real MySQL in CI, ~6 new integration tests |
| T6.3 Config cleanup | 2 | properties → YAML, @ConfigurationProperties |
| **TOTAL** | **7 days** | |

---

## 13. Master Task Timeline

```
Week 1-2:   Sprint 0 — Safety-Net Tests                           (14 days, ~104 new tests)
Week 3-4:   Sprint 1 — Break Monoliths + Infra Quick Wins         (13 days, 5 classes split)
Week 5-7:   Sprint 2 — Unified Screener + Elliott Merge           (14 days, Screener<T> interface)
Week 7-8:   Sprint 3 — Unified Strategy + Analysis Merge          (5 days, 2 frameworks → 1)
Week 9-10:  Sprint 4 — Broker Abstraction + Event Bus             (8 days, BrokerFacade + events)
Week 11-13: Sprint 5 — The Big Package Move                       (13 days, ~280 files reorganized)
Week 13-14: Sprint 6 — Post-Refactor Tests + Config               (7 days, Testcontainers + YAML)
─────────────────────────────────────────────────────────────────
TOTAL:      ~74 working days (~15 weeks)
```

**Test count progression**:
```
Start:      184 tests
After S0:   ~288 tests (+104 safety net)
After S1:   ~308 tests (+20 post-split)
After S2:   ~330 tests (+22 screener/Elliott)
After S3:   ~335 tests (+5 verify)
After S4:   ~350 tests (+15 broker/event)
After S5:   ~355 tests (+5 verify)
After S6:   ~385 tests (+30 contract/integration/config)
```

**No-regression checkpoints** (must pass before starting next sprint):
- `./gradlew test` — all green
- `./gradlew compileJava` — clean compile
- Manual smoke: copilot analysis, chart render, trade entry (paper mode), screener run

---

## 14. Testing Philosophy & Constraints

**Updated 2026-04-24 after cleaning broken tests.**

### Baseline
- **Before cleanup**: 184 tests, 10 failing (OOM, schema mismatch, stale mocks)
- **After cleanup**: 182 tests, 0 failing — deleted 4 broken test files, fixed 3 stale mock stubs
- **Deleted**: `DoubleTopBottomBacktestTest` (OOM), `ElliottWaveBacktestSimulator` (DoubleNum/DecimalNum), `TradeLifecycleE2ETest` (schema mismatch x6), `TradingSystemIntegrationTest` (schema mismatch)
- **Fixed**: `InstrumentResolverServiceTest` (exchange array), `TradeOrchestrationServiceTest` (5-param resolve + strategyType), `KiteOrderManagerTest` (null disclosedQuantity)

### Hard Rules

1. **Full suite must complete in under 5 minutes.** Any test that makes the suite slower than this budget must be optimized or removed.

2. **Default to unit tests with mocks.** Every service being refactored gets unit tests that mock all dependencies. This is fast, isolated, and sufficient for catching regressions from class splits and package moves.

3. **Integration tests share a single server start.** If integration tests are needed, they must be organized so the Spring context boots ONCE and all integration tests run against it. Use `@SpringBootTest` with `@TestMethodOrder` in a single test class, or use a shared context via `@DirtiesContext(classMode = NEVER)`. Do NOT start the server per test class.

4. **No heavyweight backtests in CI.** Backtests that load large datasets or run full simulations belong in a separate Gradle task (`./gradlew heavyTest`) with its own heap settings, NOT in the default `./gradlew test`.

### Test Structure Going Forward

```
src/test/java/
├── com/dtech/
│   ├── trade/service/          — Unit tests with mocks (TradeEntryHandler, TradeExit, etc.)
│   ├── screener/               — Unit tests for each screener adapter + unified controller
│   ├── copilot/                — Unit tests for skill CRUD, prompt builders, orchestrator
│   ├── strategy/               — Unit tests for builder, runner, simulation
│   ├── analysis/               — Unit tests for chart data, indicator compute, scoring
│   ├── broker/                 — Contract tests for BrokerFacade implementations
│   ├── alert/                  — Unit tests for queue, channel dispatch
│   ├── market/                 — Unit tests for data provider, bar series cache
│   ├── ta/elliott/             — Existing 88+ Elliott tests (keep as-is, they're fast)
│   └── integration/            — ONE shared-context integration test class
│       └── SharedContextIntegrationTest.java  — boots once, runs all integration scenarios
```

### Pre-Refactor Test Pattern

For each class being split/moved, write tests BEFORE the refactor:

```java
@ExtendWith(MockitoExtension.class)
class SomeServiceTest {
    @Mock private DependencyA depA;
    @Mock private DependencyB depB;
    @InjectMocks private SomeService service;

    @Test void methodX_happyPath() { ... }
    @Test void methodX_edgeCase() { ... }
    @Test void methodY_returnsCorrectFormat() { ... }
}
```

These tests pin the current behaviour. After the refactor, the same assertions must still pass (possibly against the new class name).

### Sprint 0 Revision

Given the testing constraints, Sprint 0 is revised:
- All safety-net tests are **unit tests with mocks** (no Spring context boot)
- Target: each test class runs in <2 seconds
- No integration tests in Sprint 0 — those come in Sprint 6 as a single shared-context class
- The OOM-prone backtest tests are NOT rewritten — backtesting is verified manually or via a separate heavy task

---

## 15. UI / Frontend Refactoring Plan

**Codebase**: 90 files, ~13,100 LOC, React 18 + TypeScript + Vite  
**Charting**: lightweight-charts 4.1 + TradingView Charting Library  
**State**: React Context (auth only) + local useState everywhere  
**Styling**: 90% inline styles, no design system  

---

### 15.1 Current State — Key Problems

#### A. Monolithic Components (>500 LOC)

| Component | LOC | Responsibilities |
|-----------|-----|-----------------|
| `legacy-chart/ProApp.tsx` | 1,056 | Chart rendering + indicator overlay + drawing tools + state persistence + multi-panel |
| `tradingview/SnapshotDialog.tsx` | 904 | Snapshot CRUD + tagging + comments + likes + sharing + search |
| `tradingview/AnalysisPanel.tsx` | 846 | AI analysis trigger + streaming response + hypothesis display + action buttons |
| `tradingview/TVChartApp.tsx` | 814 | Multi-tab workspace + symbol selection + timeframe + layout persistence + WebSocket |
| `pipeline/pages/PipelinePage.tsx` | 631 | Signal list + filtering + entry/exit actions + status polling + pagination |
| `pages/ZigZagViewer.tsx` | 587 | ZigZag detection + chart rendering + indicator overlay |
| `screener/components/ScreenerForm.tsx` | 514 | Kotlin script editor + validation + mapping config + workflow steps |
| `patternScanner/pages/PatternScreenerPage.tsx` | 481 | Pattern scan trigger + results display + polling + chart links |

#### B. Duplicate Features

| Feature | Location 1 | Location 2 | Overlap |
|---------|-----------|-----------|---------|
| Pattern screening | `patternScreener/` (scheduled, persistent) | `patternScanner/` (on-demand) | Different APIs, similar UI, could share result cards + chart links |
| Trade action timeline | `trades/TradeActionTimeline.tsx` | `tradeMonitor/components/TradeActionTimelineDark.tsx` | Same logic, different theme |
| Multi-panel chart | `legacy-chart/MultiPanelChart.tsx` | `tradingview/TVMultiPanelChart.tsx` | Both permanent — extract shared utilities (time formatting, indicators) |
| Chart panel | `legacy-chart/SingleChartPanel.tsx` | `tradingview/TVChartContainer.tsx` | Both permanent — different libraries for different use cases |

#### C. 288 `any` Types

Heavy use of `any` in state, event handlers, API responses, and callback functions. Kills refactoring safety — can't catch type errors at compile time.

#### D. No Shared Infrastructure

- No custom hooks (`useFetch`, `useLocalStorage`, `usePolling`)
- No reusable UI components (Spinner, ErrorBanner, EmptyState, Card, Modal)
- No design tokens (colors hardcoded as `#1a1a1a`, `#90caf9`, etc.)
- No Error Boundary
- Inline styles on every element — no consistency guarantee

#### E. Legacy Chart System

`legacy-chart/` (ProApp, 1056 LOC) uses lightweight-charts directly. `tradingview/` uses TradingView Charting Library. Both charting systems serve different purposes and will coexist permanently. Refactoring should treat both as first-class citizens — shared utilities (time formatting, indicator calculations) should be extracted to work with both.

---

### 15.2 Target UI Architecture

```
src/
├── app/
│   ├── main.tsx              — Entry point
│   ├── App.tsx               — Router + layout + providers
│   └── routes.tsx            — All route definitions (extracted from main.tsx)
│
├── components/               — Shared, reusable UI components
│   ├── ui/                   — Primitives: Button, Card, Modal, Spinner, Badge, EmptyState
│   ├── layout/               — Layout, Sidebar, Header, ProtectedRoute
│   ├── chart/                — ChartPanel, MultiPanelChart, TimeframeSelector
│   ├── feedback/             — ErrorBanner, ErrorBoundary, LoadingSkeleton, Toast
│   └── data-display/         — DataTable, Timeline, StatusBadge
│
├── features/                 — Feature modules (self-contained)
│   ├── auth/                 — Login, KiteLogin, AuthContext, authApi
│   ├── dashboard/            — Dashboard page
│   ├── chart/                — TVChartApp (split into smaller pieces), workspace management
│   ├── analysis/             — AnalysisPanel, SnapshotDialog (split), AI chat
│   ├── screener/             — Unified: DSL screener + Elliott screener
│   ├── scanner/              — Unified: pattern scanner + pattern screener
│   ├── pipeline/             — Trade signal pipeline
│   ├── trades/               — Trade history, detail, timeline
│   ├── trade-monitor/        — Live trade monitoring
│   ├── scan/                 — On-demand scanning
│   ├── settings/             — User settings, AI provider config
│   └── admin/                — Kite config, groups, segment config
│
├── hooks/                    — Custom hooks
│   ├── useFetch.ts           — Consistent data loading with loading/error state
│   ├── useLocalStorage.ts    — Type-safe localStorage with SSR safety
│   ├── usePolling.ts         — Interval-based polling with cleanup
│   ├── useWebSocket.ts       — STOMP WebSocket connection management
│   └── useAuth.ts            — Auth shortcut (thin wrapper on AuthContext)
│
├── api/                      — All API call functions (grouped by domain)
│   ├── chartApi.ts
│   ├── screenerApi.ts
│   ├── tradeApi.ts
│   ├── analysisApi.ts
│   ├── pipelineApi.ts
│   └── adminApi.ts
│
├── types/                    — Shared TypeScript interfaces
│   ├── chart.ts
│   ├── trade.ts
│   ├── screener.ts
│   ├── analysis.ts
│   └── common.ts             — Pagination, ApiError, LoadingState
│
├── config/
│   ├── api.ts                — getApiUrl (existing)
│   └── theme.ts              — Color tokens, spacing, typography
│
├── utils/
│   ├── apiHelper.ts          — withAuth (existing)
│   ├── format.ts             — Date, number, currency formatters
│   └── storage.ts            — localStorage helpers with JSON parse safety
│
└── styles/
    ├── global.css            — CSS reset + base styles
    └── variables.css         — CSS custom properties (design tokens)
```

---

### 15.3 UI Sprint Breakdown

---

#### TUI-0 — Shared Infrastructure (3 days)

Build the foundation that every other task depends on.

**Day 1 — Design tokens + base components**:
- Create `config/theme.ts` with color palette, spacing scale, border radii
  ```ts
  export const colors = {
    bg: { primary: '#0a0a0a', secondary: '#1a1a1a', surface: '#242424' },
    text: { primary: '#ffffff', secondary: '#b0b0b0', muted: '#666666' },
    accent: { blue: '#90caf9', green: '#4caf50', red: '#f44336', yellow: '#ffb74d' },
    border: { default: '#333333', hover: '#555555' },
  };
  ```
- Create `styles/variables.css` — CSS custom properties from tokens
- Create base components: `Spinner`, `ErrorBanner`, `EmptyState`, `Badge`, `Card`

**Day 2 — Custom hooks**:
- `useFetch<T>(url, options)` — returns `{ data, loading, error, refetch }`
- `useLocalStorage<T>(key, defaultValue)` — type-safe, handles JSON parse errors
- `usePolling(callback, intervalMs, enabled)` — with cleanup on unmount
- `useWebSocket(topic, onMessage)` — wraps STOMP client lifecycle

**Day 3 — Error Boundary + types foundation**:
- Create `ErrorBoundary` component (catches render errors, shows fallback UI)
- Wrap app root in ErrorBoundary
- Create `types/common.ts`: `ApiError`, `PaginatedResponse<T>`, `LoadingState`
- Create `api/` directory — move all scattered `*Api.ts` files into centralized location

**Done when**: Hooks work in isolation tests, base components render, ErrorBoundary catches thrown errors.

---

#### TUI-1 — Kill 288 `any` Types (3 days)

**Day 1 — API response types**:
- Define interfaces for every API response that currently uses `any`
- Focus on: trade types, screener types, analysis types, chart data types
- Place in `types/` directory

**Day 2 — Component state + event handlers**:
- Replace `useState<any>` with proper types
- Replace `(e: any)` event handlers with `React.ChangeEvent<HTMLInputElement>`, etc.
- Replace `as any` casts with proper type assertions or type guards

**Day 3 — Strict mode + cleanup**:
- Enable `"strict": true` in tsconfig if not already
- Add `"noImplicitAny": true`
- Fix remaining compile errors
- Target: 0 `any` instances (or <10 for genuinely untyped third-party libs like TradingView)

**Done when**: `npx tsc --noEmit` passes with strict mode, `grep -r ": any" src/ | wc -l` < 10.

---

#### TUI-2 — Split Monolithic Components (5 days)

**Day 1 — Split TVChartApp (814 LOC → 4 components)**:
- `TVChartApp.tsx` → orchestrator only (~150 LOC)
- `WorkspaceTabBar.tsx` — tab management, create/rename/delete tabs
- `SymbolTimeframeBar.tsx` — symbol input, timeframe selector, layout picker
- `ChartWorkspaceManager.ts` — localStorage persistence for workspace state (non-component)

**Day 2 — Split SnapshotDialog (904 LOC → 4 components)**:
- `SnapshotDialog.tsx` → thin dialog shell (~150 LOC)
- `SnapshotForm.tsx` — create/edit form with tag input
- `SnapshotList.tsx` — search + filter + paginated list
- `SnapshotCard.tsx` — individual snapshot with comments/likes

**Day 3 — Split AnalysisPanel (846 LOC → 3 components)**:
- `AnalysisPanel.tsx` → panel container (~150 LOC)
- `AnalysisTrigger.tsx` — trigger button, loading state, prompt selection
- `AnalysisResults.tsx` — observations, hypotheses, flags display with actions

**Day 4 — Split PipelinePage (631 LOC → 3 components)**:
- `PipelinePage.tsx` → page shell with filters (~150 LOC)
- `SignalCard.tsx` — individual signal with entry/exit actions
- `PipelineFilters.tsx` — status, direction, strategy type filters

**Day 5 — Split remaining >500 LOC components**:
- `ProApp.tsx` (1056 LOC) — if still in use, split into ChartCore + IndicatorOverlay + DrawingToolbar. If deprecated, mark with `@deprecated` comment.
- `ScreenerForm.tsx` (514 LOC) → ScriptEditor + MappingConfig + WorkflowConfig
- `PatternScreenerPage.tsx` (481 LOC) → PatternScreenerPage + PatternResultList + PatternCard

**Done when**: No component exceeds 250 LOC. `npm run build` succeeds. All pages render correctly.

---

#### TUI-3 — Consolidate Duplicates (3 days)

**Day 1 — Merge trade timelines**:
- Keep `TradeActionTimelineDark.tsx` (newer), delete `TradeActionTimeline.tsx` (legacy)
- Add `variant?: 'light' | 'dark'` prop if light theme is needed
- Update all imports

**Day 2 — Merge pattern scanner + screener result cards**:
- Create shared `PatternResultCard.tsx` in `components/data-display/`
- Both `patternScanner/` and `patternScreener/` use it
- Different data sources, same display component
- Optionally merge pages if they're similar enough (or keep separate with shared components)

**Day 3 — Extract shared chart utilities**:
- Identify common logic between `legacy-chart/` and `tradingview/` (time formatting, indicator helpers, symbol resolution)
- Extract into `components/chart/` as shared utilities usable by both systems
- Both charting systems are permanent — ensure shared components work with both

**Done when**: No duplicate components serve the same purpose. Shared chart utilities extracted, both charting systems have clean boundaries.

---

#### TUI-4 — Centralize API Layer (2 days)

**Day 1 — Move all API functions to `api/`**:
- Currently scattered: `elliottScreener/api/`, `scan/api/`, `pipeline/api/`, `trades/api/`, `patternScanner/api/`, etc.
- Move to centralized `api/` directory, grouped by domain
- Ensure ALL use `apiFetch()` + `withAuth()` (audit for any bare `fetch()` calls)

**Day 2 — Add response type validation**:
- Create lightweight runtime type checks for critical API responses (trade data, analysis results)
- Optional: use `zod` for runtime validation of API shapes
- Add error normalization: all API errors become `ApiError { code, message, details? }`

**Done when**: All API calls live in `api/`, all use `withAuth()`, all have TypeScript return types.

---

#### TUI-5 — Extract Routes + Slim main.tsx (1 day)

**Day 1**:
- Extract route definitions from `main.tsx` (~400 LOC of routes) into `app/routes.tsx`
- `main.tsx` becomes: providers → Layout → Routes (< 30 LOC)
- Group routes by feature in `routes.tsx` with comments
- Lazy-load heavy feature pages with `React.lazy()` + `Suspense`
  ```tsx
  const TVChartApp = lazy(() => import('../features/chart/TVChartApp'));
  ```
- This reduces initial bundle size — chart and analysis code loaded on demand

**Done when**: `main.tsx` < 30 LOC, lazy routes work, no flash of empty content (Suspense fallback shows Spinner).

---

#### TUI-6 — Inline Styles → CSS Modules or Design Tokens (3 days)

**Day 1 — High-traffic components**:
- Convert inline styles in Dashboard, TVChartApp, PipelinePage to use CSS custom properties from `variables.css`
- Pattern: replace `style={{ color: '#90caf9' }}` with `style={{ color: 'var(--accent-blue)' }}` (incremental, not a full rewrite)

**Day 2 — Remaining components**:
- Convert remaining feature pages
- Extract repeated inline style objects into named constants at top of file:
  ```tsx
  const styles = { card: { background: 'var(--bg-surface)', ... } };
  ```

**Day 3 — Responsive basics**:
- Add `@media` queries for key breakpoints (mobile trade monitoring is useful)
- Ensure sidebar collapses on small screens
- Test on 1024px and 768px widths

**Done when**: No hardcoded color hex values in TSX files. All colors come from theme tokens.

---

#### TUI-7 — Unified Header, Footer & Page Layout System (3 days)

**Why**: Currently each page builds its own header/navigation and has inconsistent spacing, padding, and layout structure. Users experience a disjointed UI when navigating between features.

**Day 1 — Unified Header + Sidebar Navigation**:
- Create `components/layout/AppHeader.tsx` — consistent top bar across all pages:
  - App logo/name (left)
  - Current page title / breadcrumb (center-left)
  - Symbol quick-search (center, optional per page)
  - User avatar + role badge + logout (right)
  - Kite connection status indicator (right)
- Create `components/layout/AppSidebar.tsx` — persistent navigation:
  - Group links by domain: Charts, Screeners, Trading, Admin
  - Active route highlighting
  - Collapsible on small screens
  - Badge counts (active trades, pending signals)
- Create `components/layout/AppFooter.tsx` — minimal footer:
  - Market status (open/closed/pre-market)
  - Last data sync timestamp
  - Version info

**Day 2 — Page Layout Shell**:
- Create `components/layout/PageShell.tsx` — standard wrapper for every page:
  ```tsx
  <PageShell title="Pipeline" subtitle="Active trade signals">
    {children}
  </PageShell>
  ```
  - Consistent padding, max-width, page title styling
  - Optional action bar (top-right buttons per page)
  - Optional filter bar slot
  - Scroll container with consistent behavior
- Create `components/layout/PageSection.tsx` — for grouping content within a page:
  ```tsx
  <PageSection title="Recent Signals" collapsible>
    {children}
  </PageSection>
  ```
- Define standard page spacing: `--page-padding: 24px`, `--section-gap: 16px`, `--card-gap: 12px`

**Day 3 — Migrate all pages + consistency audit**:
- Wrap every page component in `PageShell`:
  - Dashboard, TVChartApp, PipelinePage, TradesSummaryPage, ScanPage, ScreenerListPage, ElliottScreenerPage, PatternScreenerPage, SettingsPage, all admin pages
- Remove per-page ad-hoc headers/titles (replaced by PageShell title prop)
- Consistency audit checklist:
  - [ ] Every page has a title visible in the header
  - [ ] Every page uses PageShell for padding/spacing
  - [ ] Every data table uses the same column styling
  - [ ] Every action button uses the same Button component from TUI-0
  - [ ] Every empty state uses EmptyState component from TUI-0
  - [ ] Every loading state uses Spinner component from TUI-0
  - [ ] Every error uses ErrorBanner component from TUI-0
  - [ ] Color palette matches theme tokens (no hardcoded hex outside theme.ts)
- Fix any pages that fail the audit

**Done when**: Every page renders inside PageShell with consistent header/sidebar/footer. Navigation works from any page. Visual consistency across all routes — same fonts, spacing, colors, button styles.

---

#### TUI-8 — Mobile-Responsive Design (3 days)

**Why**: The entire UI is built with fixed widths and desktop-only layouts. Trade monitoring and signal alerts are time-sensitive — users need to check positions, approve entries, and see screener results from their phone.

**Day 1 — Responsive layout foundation**:
- Add responsive breakpoints to `styles/variables.css`:
  ```css
  :root {
    --bp-mobile: 480px;
    --bp-tablet: 768px;
    --bp-desktop: 1024px;
    --bp-wide: 1440px;
  }
  ```
- Make `AppSidebar` collapsible:
  - Desktop (>1024px): permanent sidebar
  - Tablet (768-1024px): collapsed icon-only sidebar, expand on hover
  - Mobile (<768px): hidden, hamburger menu toggle, slide-over overlay
- Make `AppHeader` responsive:
  - Desktop: full header with all elements
  - Mobile: logo + hamburger + user avatar only, symbol search moves to page-level
- `PageShell` padding: `24px` desktop → `12px` mobile
- Add `useMediaQuery(breakpoint)` hook for programmatic responsive behavior

**Day 2 — Critical mobile pages**:
Priority pages that MUST work on mobile (these are time-sensitive in production):

1. **Dashboard** — stack cards vertically, full-width on mobile
2. **PipelinePage** — signal cards stack vertically, swipe-friendly action buttons, larger touch targets (min 44px)
3. **TradeMonitor** — position cards full-width, P&L numbers large and readable, entry/exit buttons prominent
4. **TradesSummaryPage** — horizontal scroll on data table, or switch to card layout on mobile
5. **Trade alerts (Toast)** — already floating, ensure it doesn't cover action buttons on small screens

For each:
- Replace `display: flex` row layouts with column on mobile via media query
- Replace fixed `width: 300px` with `width: 100%` or `max-width`
- Ensure font sizes are readable (min 14px body, 12px secondary)
- Touch targets minimum 44x44px (Apple HIG)

**Day 3 — Remaining pages + testing**:
- Make remaining pages responsive (screener, scan, settings, admin) — these are less urgent but should not break
- Chart pages (TVChartApp, legacy-chart): set min-width rather than forcing full responsive — charts need screen real estate, but should not overflow/scroll horizontally
- Test all pages at 3 widths: 375px (iPhone SE), 768px (iPad), 1440px (desktop)
- Fix any overflow, cut-off text, unreachable buttons, or unreadable content
- Add `<meta name="viewport" content="width=device-width, initial-scale=1">` to index.html if missing

**Done when**: Dashboard, Pipeline, TradeMonitor, and Trades pages fully usable on 375px-width screen. No horizontal scroll on any page. Sidebar collapses properly. All touch targets ≥44px.

---

### 15.4 UI Sprint Summary

| Task | Days | What Changes |
|------|------|-------------|
| TUI-0 Shared Infrastructure | 3 | Hooks, base components, ErrorBoundary, design tokens |
| TUI-1 Kill `any` Types | 3 | 288 → <10 `any` instances, strict TypeScript |
| TUI-2 Split Monoliths | 5 | 8 large components → ~25 focused components, max 250 LOC |
| TUI-3 Consolidate Duplicates | 3 | Merge timelines, pattern cards, decide legacy chart fate |
| TUI-4 Centralize API Layer | 2 | Scattered API files → single `api/` directory |
| TUI-5 Extract Routes | 1 | main.tsx <30 LOC, lazy-loaded feature routes |
| TUI-6 Design Tokens | 3 | Inline styles → CSS variables, responsive basics |
| TUI-7 Unified Layout | 3 | Consistent header, footer, sidebar, PageShell across all pages |
| TUI-8 Mobile Responsive | 3 | Responsive breakpoints, collapsible sidebar, mobile-first critical pages |
| **TOTAL** | **26 days** | |

**Run in parallel with backend sprints** — UI tasks have no dependency on backend refactoring (API endpoints stay the same).

---

### 15.5 UI Testing Strategy

**No UI tests exist today.** Given the 5-minute suite constraint:

- **Don't add**: Cypress E2E tests (slow, flaky, requires running server)
- **Do add** (Sprint 6 or later):
  - Vitest unit tests for custom hooks (`useFetch`, `usePolling`, `useLocalStorage`)
  - Vitest + React Testing Library for critical components: `ProtectedRoute`, `ErrorBoundary`, `TradeActionTimeline`
  - Target: ~30 UI tests, <30 seconds total
  - Add to existing `./gradlew test` via npm script in Gradle build

---

## 16. Updated Master Timeline (Backend + UI)

```
Weeks 1-2:   Sprint 0 — Safety-net tests                    (14 days)
             TUI-0 — Shared UI infrastructure                (3 days, parallel)
Weeks 3-4:   Sprint 1 — Break monoliths + infra              (13 days)
             TUI-1 — Kill any types                          (3 days, parallel)
Weeks 5-7:   Sprint 2 — Unified screener + Elliott           (14 days)
             TUI-2 — Split UI monoliths                      (5 days, parallel)
Weeks 7-8:   Sprint 3 — Strategy + analysis merge            (5 days)
             TUI-3 — Consolidate UI duplicates               (3 days, parallel)
Weeks 9-10:  Sprint 4 — Broker abstraction + events          (8 days)
             TUI-4+5 — Centralize API + extract routes       (3 days, parallel)
Weeks 11-13: Sprint 5 — Big package move                     (13 days)
             TUI-6 — Design tokens + responsive              (3 days, parallel)
Weeks 12-13: Sprint 5 continued                               
             TUI-7 — Unified layout + consistency audit       (3 days, parallel)
             TUI-8 — Mobile responsive design                  (3 days, parallel)
Weeks 13-14: Sprint 6 — Post-refactor tests + config         (7 days)
─────────────────────────────────────────────────────────────────────
Backend:     ~74 working days
UI:          ~26 working days (parallel)
Total wall:  ~15 weeks (no additional time for UI — runs in parallel)
```

---

## 17. API Contract Management — Backend ↔ UI Coordination

### 17.1 Core Rule: No Breaking API Changes

**Every `@RequestMapping` URL path stays exactly the same throughout the refactor.** Package moves (Sprint 5) only change Java package names — the HTTP paths are in annotations and don't change. The UI never knows or cares which Java package a controller lives in.

### 17.2 API Change Inventory

| Sprint | API Change | Type | UI Impact |
|--------|-----------|------|-----------|
| Sprint 1 (T1.1-T1.4) | None — internal class splits only | None | Zero |
| Sprint 1 (T1.5) | None — Flyway is DB-only | None | Zero |
| Sprint 2 (T2.5) | **NEW** `/api/screeners/{type}/run` | Additive | UI can migrate when ready |
| Sprint 2 (T2.5) | **NEW** `/api/screeners/{type}/results` | Additive | UI can migrate when ready |
| Sprint 2 (T2.5) | Old screener endpoints **deprecated** (not removed) | Soft deprecation | Zero until UI migrates |
| Sprint 3 | None — internal merges | None | Zero |
| Sprint 4 (T4.1) | None — BrokerFacade is internal | None | Zero |
| Sprint 4 (T4.3) | None — event bus is internal | None | Zero |
| Sprint 5 | None — `@RequestMapping` paths unchanged | Package moves only | Zero |
| Sprint 6 | None | None | Zero |

**Summary**: Only Sprint 2 introduces new endpoints, and they're additive — old endpoints keep working until explicitly removed.

### 17.3 Deprecation Protocol

When a new unified endpoint replaces old ones:

1. **Backend adds new endpoint** (e.g., `/api/screeners/elliott/run`)
2. **Old endpoint stays** (e.g., `/api/elliott-screener/{id}/run`) — marked `@Deprecated` in Java, response includes `X-Deprecated: true` header
3. **UI migrates** at its own pace (TUI-4 is a natural time to do this)
4. **Old endpoint removed** only after UI migration is verified in production (at least 1 release cycle)

### 17.4 Coordinated UI Migration Tasks

These tasks should be added to the UI backlog and executed AFTER the corresponding backend sprint is complete:

#### TUI-API-1 — Migrate to Unified Screener Endpoints (1 day, after Sprint 2)

**Depends on**: T2.5 (unified screener controller deployed)

- Update `elliottScreener/api/` to call `/api/screeners/elliott/run` instead of `/api/elliott-screener/{id}/run`
- Update `patternScanner/api/` to call `/api/screeners/pattern/run` instead of old endpoint
- Update `screener/api/` (DSL) to call `/api/screeners/dsl/run`
- Update result-fetching to use `/api/screeners/{type}/results`
- Verify all screener UIs still work with new endpoints
- **Only then** can old endpoints be removed from backend

#### TUI-API-2 — API Response Type Sync (1 day, during TUI-1)

When killing `any` types in TUI-1, ensure the TypeScript interfaces match the ACTUAL backend response shapes (not guesses). For each API domain:

- Hit the endpoint locally, capture the JSON response
- Define the TypeScript interface from the real response
- Use that interface in the API call function
- This prevents type drift between backend DTOs and frontend interfaces

### 17.5 Safety Net: API Contract Tests

To prevent accidental URL breakage during package moves, add a lightweight contract check:

```java
// src/test/java/com/dtech/web/ApiContractTest.java
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTest {
    @Autowired MockMvc mockMvc;

    // Verify every public endpoint still responds (not 404)
    @ParameterizedTest
    @ValueSource(strings = {
        "/api/trade-orders/",
        "/api/trade-signals",
        "/api/elliott-screener",
        "/api/scan",
        "/api/charts/tradingview",
        "/api/segment-config/",
        "/api/simulation/status",
        // ... all public endpoints
    })
    void endpointExists(String path) throws Exception {
        mockMvc.perform(get(path)
            .header("Authorization", "Bearer test-token"))
            .andExpect(status().isNot(equalTo(404)));
    }
}
```

This test runs in <2 seconds and catches any accidental 404s from wrong `@ComponentScan` or missing controller registration after package moves. Add it in Sprint 0 (T0.1 timeframe) so it protects every subsequent sprint.

### 17.6 Updated Timeline with Coordination Points

```
Sprint 2 (backend) ──── T2.5 deploys unified screener endpoints
                         │
                         ▼
                   TUI-API-1 ──── UI migrates to new endpoints (1 day)
                         │
                         ▼
                   Old endpoints removed (next release)

Sprint 5 (backend) ──── Package moves, @RequestMapping unchanged
                         │
                         ▼
                   ApiContractTest verifies no 404s ──── UI unaffected
```

No UI work blocks backend work. No backend work blocks UI work. The only coordination point is TUI-API-1 after Sprint 2, and it's 1 day of work.


---

## 18. Branch Strategy — Refactor Integration Branch

### 18.1 Why an Interim Branch

Direct-to-master refactoring is risky: a Sprint 5 package move that breaks something means reverting 280 file renames from master while production is down. Instead, all refactoring work flows through an interim integration branch where it gets tested before reaching master.

### 18.2 Branch Structure

```
master (production)
  │
  └── refactor/integration          ← Long-lived interim branch, all refactor PRs target this
        │
        ├── refactor/t0.1-trade-safety-net       ← Sprint 0 task branches
        ├── refactor/t0.2-screener-safety-net
        ├── refactor/t1.1-split-copilot-skill
        ├── refactor/t1.2-split-tradingview-chart
        ├── ...
        └── refactor/tui-0-shared-infra
```

### 18.3 Workflow

```
1. Sync master into integration first (EVERY time, before EVERY task)
   git checkout refactor/integration
   git pull
   git merge master
   # Resolve conflicts if any, push

2. Create task branch from refactor/integration
   git checkout -b refactor/t1.1-split-copilot-skill

3. Do the work, commit, push

4. PR → refactor/integration (NOT master)
   - Code review
   - CI runs full test suite
   - Merge when green

5. After each Sprint completes:
   - Full regression on refactor/integration
   - Manual smoke test (copilot, chart, trade entry, screener)
   - PR: refactor/integration → master
   - This is the only merge to master — one per sprint, well-tested
```

### 18.4 Rules

| Rule | Why |
|------|-----|
| **Sync master before every new task** | `git checkout refactor/integration && git merge master` before creating task branch — avoids surprises |
| **Task branches branch from `refactor/integration`, not master** | So they build on previous refactor work |
| **Task PRs target `refactor/integration`, not master** | Isolate refactor from production |
| **Master → `refactor/integration` sync weekly** | Pick up hotfixes and feature work that landed on master |
| **`refactor/integration` → master once per sprint** | Batched, tested, reviewed merge to production |
| **Hotfixes go to master directly** (as today) | Production bugs don't wait for refactor |
| **Non-refactor features go to master directly** (as today) | Normal dev workflow unchanged |
| **No force-pushes on `refactor/integration`** | Others may have branches off it |

### 18.5 Sync Protocol: master → refactor/integration

Hotfixes and feature work will land on master while refactoring is in progress. To avoid drift:

```bash
# Weekly (or after any master merge):
git checkout refactor/integration
git merge master
# Resolve any conflicts
git push
```

If conflicts arise (a hotfix touched a file being refactored), resolve on the integration branch — never on master.

### 18.6 Sprint-End Merge: refactor/integration → master

At the end of each sprint, after all tasks are merged to `refactor/integration`:

```
1. Sync master into refactor/integration one final time
2. Run full test suite: ./gradlew test
3. Run UI build: cd ui/chart-draw-app && npm run build
4. Manual smoke test checklist:
   - [ ] Login works
   - [ ] Chart loads with indicators
   - [ ] Copilot analysis triggers and returns results
   - [ ] Screener runs (DSL, Elliott, Pattern)
   - [ ] Pipeline shows signals, entry/exit works (paper mode)
   - [ ] Trade monitor shows positions
   - [ ] All admin pages load
5. Create PR: refactor/integration → master
   - Title: "Refactor Sprint X — [summary]"
   - Body: list of all task PRs included
6. Merge (squash or merge commit — team preference)
7. Tag: git tag refactor-sprint-X-complete
```

### 18.7 Setup (Do Once Before Sprint 0)

```bash
git checkout master
git pull
git checkout -b refactor/integration
git push -u origin refactor/integration
```

### 18.8 Rollback

If a sprint merge to master causes production issues:

```bash
# Revert the single merge commit
git revert -m 1 <merge-commit-hash>
git push
```

One commit to revert, not 20 individual task commits. This is the key benefit of the integration branch.


---

## 19. Python Code — Assessment & Plan

### 19.1 Current State

**35 Python files, ~10,300 LOC** across 4 locations:

| Location | Files | Purpose | Status |
|----------|-------|---------|--------|
| `src/main/python/` | 2 | Matplotlib chart rendering via Py4J bridge | Active, production |
| `scripts/` | 1 | `benchmark_simulation.py` — backtest evaluator | Active, utility |
| `strategies/impulse/python/` | 10 | XGBoost impulse training, FastAPI prediction server, RL environments | Active, production |
| `ds-python/finrl-poc/` | 22 | RL research: training, evaluation, trade filtering, prediction serving | Active, research |

### 19.2 Key Problem: Duplication

`ds-python/finrl-poc/service/` contains **7 exact copies** of files from `strategies/impulse/python/`:
- `train_impulse_model.py`, `train_impulse_exit.py`, `backtest_impulse_model.py`
- `export_impulse_trades.py`, `prediction_server.py`
- All 5 `env/*.py` files (trading_env, entry_env, exit_env, profit_extension_env, trailing_stop_env)

### 19.3 Decision: Keep As-Is (With Cleanup)

The Python code is a separate ML/RL pipeline with its own deployment lifecycle (FastAPI on port 8501, Flask on port 5001). It doesn't share packages or deploy with the Java backend. Refactoring it alongside Java would add risk for no benefit.

**What we WILL do:**
- Delete the 7 duplicate files in `ds-python/finrl-poc/service/` (or replace with symlinks to `strategies/impulse/python/`)
- Add `__pycache__/` and `*.pyc` to `.gitignore` if not already
- Add `ds-python/finrl-poc/models/*.zip` to `.gitignore` and remove from git history
- Add a `requirements.txt` at project root that references the two Python environments

**What we WON'T do:**
- Restructure the Python code — it works, it's isolated, and it's not part of the Java refactor scope
- Merge `ds-python/` into `strategies/` — they serve different purposes (research vs production)

---

## 20. Repository Cleanup — Remove Junk Files

### 20.1 Files to Remove from Git

| File | Size | Action |
|------|------|--------|
| `ds-python/finrl-poc/models/ppo_entry_timer.zip` | 180KB | Remove, add `*.zip` to `.gitignore` |
| `ds-python/finrl-poc/models/ppo_exit_optimizer.zip` | 180KB | Remove |
| `ds-python/finrl-poc/models/ppo_exit_optimizer_70.zip` | 180KB | Remove |
| `ds-python/finrl-poc/models/ppo_exit_optimizer_70_full.zip` | 180KB | Remove |
| `ds-python/finrl-poc/models/ppo_exit_optimizer_75.zip` | 180KB | Remove |
| `ds-python/finrl-poc/models/ppo_reliance_15m_v2.zip` | 145KB | Remove |
| `ds-python/finrl-poc/models/ppo_reliance_daily.zip` | 144KB | Remove |
| `ds-python/finrl-poc/models/ppo_reliance_fifteenm.zip` | 144KB | Remove |
| `ds-python/finrl-poc/models/ppo_reliance_oneh.zip` | 144KB | Remove |
| `src/main/python/__pycache__/matplotlib_chart.cpython-313.pyc` | Small | Remove |
| `README.old.md` | 4.4KB | Remove |

### 20.2 Add to .gitignore

```gitignore
# Python
__pycache__/
*.pyc
*.pyo

# ML models (store in artifact registry, not git)
*.zip
*.pkl
*.h5
*.onnx

# Tool output
graphify-out/

# Misc
*.old.md
```

### 20.3 Untracked Files — Leave or Gitignore

| File/Dir | Action |
|----------|--------|
| `graphify-out/` (104MB) | Add to `.gitignore` — regenerated by `graphify update .` |
| `docs/REFACTORING_PLAN.md` (old V1) | Delete — superseded by V2 |
| `.claude/docs/` | Already in `.gitignore` pattern |
| `.graphifyignore` | Commit — it's config for graphify tool |
| `ui/chart-draw-app/charting_library/` | Leave as submodule — needed for TradingView |

### 20.4 Cleanup Task: T-CLEAN (1 day, do before Sprint 0)

```bash
# 1. Remove tracked junk
git rm --cached ds-python/finrl-poc/models/*.zip
git rm --cached src/main/python/__pycache__/matplotlib_chart.cpython-313.pyc
git rm README.old.md
rm docs/REFACTORING_PLAN.md

# 2. Update .gitignore
# (append the patterns from 20.2)

# 3. Delete Python duplicates in ds-python
rm ds-python/finrl-poc/service/train_impulse_model.py
rm ds-python/finrl-poc/service/train_impulse_exit.py
rm ds-python/finrl-poc/service/backtest_impulse_model.py
rm ds-python/finrl-poc/service/export_impulse_trades.py
rm ds-python/finrl-poc/service/prediction_server.py
# (or replace with symlinks to strategies/impulse/python/)

# 4. Clean up ds-python env duplicates
rm ds-python/finrl-poc/env/trading_env.py
rm ds-python/finrl-poc/env/entry_env.py
rm ds-python/finrl-poc/env/exit_env.py
rm ds-python/finrl-poc/env/profit_extension_env.py
rm ds-python/finrl-poc/env/trailing_stop_env.py

# 5. Commit
git add .gitignore
git commit -m "chore: clean up tracked binaries, pyc files, and duplicates"
```

This should be the **very first task** — clean repo before refactoring begins.
