# Branch Review: `feature/elliott-wave-engine`

**Base:** `master`
**Commits:** 8 (including merge)
**Files changed:** 28 (+5,805 / -436 lines)

---

## Overview

This branch delivers two major areas of work:

1. **Two-Phase Copilot Analysis** — a redesigned AI analysis pipeline (Scan → Reason) with a skill library of 33 skills (18 chart pattern + 15 Elliott Wave), observation persistence, and a new orchestrator.
2. **Elliott Wave Engine (Layers 4–8)** — a pure-Java, multi-timeframe Elliott Wave analysis engine built on top of ZigZag pivots.

---

## 1. Two-Phase Copilot Analysis

### What changed

The original copilot analysis was a single-pass AI call. It is now split into two distinct phases:

#### Phase 1 — SCAN
- The orchestrator selects relevant skills from the library based on market structure data.
- Each selected skill runs independently and produces an `ObservationResponse` with detected patterns and `drawingPoints` for TradingView overlay rendering.
- Observations are persisted to the database via `CopilotObservation` entity.

#### Phase 2 — REASON
- A reasoning pass cross-correlates all observations from Phase 1.
- Produces hypotheses with trade setups (entry, stop, target).

### New backend components

| Component | Description |
|---|---|
| `CopilotObservation` (entity + repo) | Stores per-skill scan results with pattern type, confidence, drawing points |
| `CopilotObservationService` | CRUD + query service for observations |
| `ObservationResponse` DTO | Structured AI output for scan phase: pattern, confidence, drawingPoints, narrative |
| `ReasoningRequest` DTO | Input to the reasoning phase with all observations bundled |
| `CopilotOrchestratorController` | New REST controller (`/api/copilot/orchestrator/*`) for orchestrator test/run |
| `CopilotSkillController` | New REST controller (`/api/copilot/skills/*`) for skill CRUD + test |
| `CopilotAnalysisController` (updated) | New endpoints: `/scan`, `/reason`, `/full` replacing single-pass analysis |

### Orchestrator fix (data starvation bug)

Two bugs were resolved that caused the orchestrator to select skills without seeing market data:

1. **Stale investigation object** — `fetchAndStoreMarketStructure()` was saving data to DB but the in-memory investigation object wasn't refreshed. Fix: reload from DB after structure fetch.
2. **Empty orchestrator prompt** — even with fresh data, only `"Market structure data: available"` was included in the prompt. Fix: full market structure + ZigZag pivot data are now embedded in the orchestrator prompt.

### Skill library (33 skills)

**18 Chart Pattern Skills:** Double Bottom/Top, Head & Shoulders (regular + inverse), Ascending/Descending/Symmetrical Triangle, Rising/Falling Wedge, Bull/Bear Flag, Ascending/Descending Channel, Cup & Handle, Triple Top/Bottom, Rounding Bottom, Broadening Formation.

**15 Elliott Wave Skills:** Waves 1–5 (impulse), Waves A–C (corrective), Leading Diagonal, Ending Diagonal, Extended Wave 3, Flat Correction, Zigzag Correction, Complex Correction (WXY), Wave Triangle.

Each skill has structured fields for: chart pattern correlation, candle pattern correlation, indicator correlation, and mapped trading opportunities.

---

## 2. Skill Test / Validation Feature

A testing/validation flow was added so skills and the orchestrator can be evaluated without live data.

### How it works

- **Skill Test** (`POST /api/copilot/skills/{id}/test`): User provides symbol, timeframe, chart description, and whether the pattern is present. AI evaluates each rule field against the scenario and returns: verdict, per-rule pass/fail, analysis, and suggested changes.
- **Orchestrator Test** (`POST /api/copilot/orchestrator/test`): User provides expected skills. AI simulates skill selection and checks against expectations.
- **Apply Suggestions**: test result suggestions can be applied directly back into skill fields from the UI.

### UX improvements

- Test form inputs (symbol, timeframe, description) are persisted to `localStorage` so they survive modal close/reopen.
- Analysis results rendered with `react-markdown` (headers, bold, code blocks).
- AI prompts restructured to produce structured per-rule Markdown output with `PASS/FAIL` headers.

---

## 3. Frontend Changes

### Layout overhaul (`TVChartApp`, `ChartTabBar`, `CopilotChartPanel`)

- **New `ChartTabBar` component** — action buttons (Settings ⚙, Copilot 🧠, Analysis 📊) moved from floating overlay into the tab bar header.
- **Inline copilot panel** — `CopilotChartPanel` converted from a fixed-position overlay to a flex sibling of the TradingView chart. Opening it shrinks the chart via a CSS width transition (`0 → 400px`) so the TV chart layout is preserved.
- **Z-index fix** — sidebar panels were hidden behind the tab bar. Both panels now start at `top: 38px`.

### New UI flows

- Scan / Reason / Full buttons in the copilot panel.
- Observation cards showing detected patterns with confidence scores.
- AI chat overlay disabled (the two-phase flow replaces it).

### New types (`copilotTypes.ts`)

`ObservationResponse`, `ReasoningRequest`, `DrawingPoint`, and supporting types for the scan/reason pipeline.

---

## 4. Elliott Wave Engine (new `com.dtech.ta.elliott` package)

A fully self-contained, multi-timeframe Elliott Wave analysis engine. Entry point: `ElliottWaveAnalyzer`.

### Architecture (5 layers)

| Layer | Class | Responsibility |
|---|---|---|
| Layer 4 | `PivotIndicatorEnricher` | Enriches raw ZigZag pivots with RSI, MACD, volume, ATR context → `EnrichedPivot` |
| Layer 5 | `PivotPatternDetector` | Detects classical and Elliott patterns from enriched pivots → `PatternMatch` list |
| Layer 6 | `WaveCounter` | Generates candidate wave counts (impulse + corrective) across timeframes → `WaveCount` list |
| Layer 7 | `ScenarioBuilder` | Ranks and assembles wave counts + patterns into `WaveScenario` objects with probability scores |
| Layer 8 | `ElliottWaveAnalyzer` | Top-level orchestrator: runs layers 4–7, performs cross-TF top-down/bottom-up analysis, builds narrative |

### Key data models

- **`EnrichedPivot`** — ZigZag pivot augmented with indicator state, volume, wave context
- **`PatternMatch`** — detected pattern with type, confidence, timeframe, and `WaveContextHint` list
- **`WaveCount`** — a candidate wave count: type (impulse/zigzag/flat/triangle/expanded flat), current wave in progress, pivot list, multi-dimensional score
- **`WaveScenario`** — top-ranked scenario grouping with primary/alternate counts, entry/stop/target levels
- **`TfContext`** — per-timeframe distilled context (position, structure type, W4 support zone, child confirmations)
- **`GapLevel`** — price gaps with fill status, direction, and significance (minor/moderate/major/critical)

### Cross-timeframe analysis

`ElliottWaveAnalyzer` performs two passes:

1. **Top-down:** builds `TfContext` for each timeframe from its best-scoring wave count.
2. **Bottom-up boost:** child timeframe confirmations increase the parent's cross-TF score. For example, a child timeframe showing a ZigZag/Flat/Triangle increases the parent's W4 count score by +15.

The final output `ElliottWaveAnalysis` includes a human-readable `crossTfNarrative` string suitable for passing directly to an AI prompt.

### Gap detection

`GapDetectorService` scans each timeframe's bar series for price gaps, classifies them by significance and fill status, and flags those near the current price.

### Tests

`ElliottWaveAnalyzerInfyTest` — integration test running the full engine against INFY data.

---

## 5. Integration Tests

Two new integration test classes with real AI calls:

| Test | Coverage |
|---|---|
| `CopilotOrchestrationIntegrationTest` | End-to-end orchestration: skill selection, scan, reason phases |
| `CopilotRealAIIntegrationTest` | Real AI validation for INFY and HEROMOTOCO pattern detection |

Test configuration in `application-integration.properties`.

---

## What is NOT yet wired up

- `ElliottWaveAnalyzer` is built but **not yet connected to `CopilotAnalysisController`**. The engine exists as a standalone service but the copilot analysis endpoints don't invoke it yet.
- `ChartSnapshotService` (OHLC data in AI prompt) is listed as a TODO in the wishlist but not implemented.

---

## Summary of key files

```
src/main/java/com/dtech/ta/elliott/          ← New Elliott Wave engine (19 files)
src/main/java/com/dtech/kitecon/
  data/copilot/CopilotObservation.java        ← New observation entity
  service/copilot/CopilotSkillService.java    ← Skill library + test logic (+2600 lines)
  service/copilot/CopilotOrchestratorService ← Enriched orchestrator prompts
  service/copilot/dto/ObservationResponse     ← New scan-phase DTO
  web/copilot/CopilotAnalysisController       ← scan/reason/full endpoints
  web/copilot/CopilotOrchestratorController   ← New controller
  web/copilot/CopilotSkillController          ← New controller
src/test/java/com/dtech/
  ta/elliott/ElliottWaveAnalyzerInfyTest      ← Elliott engine integration test
  copilot/CopilotOrchestrationIntegrationTest
  copilot/CopilotRealAIIntegrationTest
ui/chart-draw-app/src/tradingview/
  ChartTabBar.tsx                             ← New component
  CopilotChartPanel.tsx                       ← Major rewrite (inline layout)
  TVChartApp.tsx                              ← Layout restructure
  copilotTypes.ts                             ← New types
  SkillBuilderPage.tsx                        ← Test/validation UI
```
