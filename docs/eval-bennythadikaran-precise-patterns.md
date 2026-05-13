# Evaluation: BennyThadikaran/precise-patterns

**Date:** 2026-05-11
**Branch:** feature/chart-pattern-eval
**Repo:** https://github.com/BennyThadikaran/precise-patterns
**Verdict:** **DO NOT PORT YET. Architectural reference only. Continue stock-pattern port.**

---

## Why I checked it

User flagged "this library" without a URL while we were mid-port of `BennyThadikaran/stock-pattern`. Best candidate was the successor by the same author, `precise-patterns`, which I'd noted earlier was actively in development. Confirming whether it's a better source than the older library.

## Status

- **Alpha, work-in-progress.** README says "🚧 Work in Progress — Early Development Stage 🚧"
- 17 stars, last commit 2026-04-26
- GPL-3.0 (same license question as parent library)
- Roadmap in `tasks.md` lists Phase 1 ("Alpha Release") goals — VCP, Double Top, Double Bottom, packaging — none ticked yet for patterns

## What's in there

- **`aggregator.py`** (17KB) — multi-timeframe candle aggregation (1m/3m/5m/15m/75m/240m/EOD); event-driven
- **`pivots.py`** (6KB) — pivot detection responding to `candle.closed` events
- **`patterns.py`** (1.2KB) — **the killer detail: contains exactly ONE pattern class (`VCP`) and its `on_pivot` method is `pass`.** No detection logic implemented yet.
- `doubly_linked_list.py` — state structure for incremental pattern building
- `dtypes.py` — TypedDict-based data models (`Candle`, `OHLC`, `Pivot`)
- Event bus + pluggable handler architecture

## Why it's not useful as a code source today

The pattern algorithms aren't there. The repo is currently a **scaffolding** + **streaming infrastructure**, not a pattern library. Porting it would mean porting empty methods.

## Architectural ideas worth noting (without porting)

Three concepts the streaming design surfaces that we should keep in mind:

1. **Event-driven pattern detection** — candle.closed → pivot.formed → pattern.formed. Pattern detectors are subscribers, not batch scanners. Useful for live trading; less useful for our current batch-scan / backtest flows.
2. **Doubly-linked-list pattern state** — incremental pattern building as new pivots arrive, rather than re-scanning whole windows each tick. Could reduce CPU in live mode if we ever migrate.
3. **Multi-timeframe aggregation as a first-class component** — clean abstraction for 1m → 3m/5m/15m/EOD without bespoke per-timeframe logic. We have something similar via `CandleService` / subscription updaters; worth a direct comparison.

None of these justify a port today. They're future-architecture considerations if we ever move from batch scan to streaming.

## Recommendation

1. **Stay the course on `stock-pattern` port.** That repo has 21 implemented pattern detectors with proven geometry. We're already on Phase 1 of the port.
2. **Bookmark `precise-patterns`** — when the author finishes the pattern algorithms there (per `tasks.md` roadmap), the geometry definitions may be cleaner / better-documented than `stock-pattern`. Re-evaluate at that point as a possible REFINEMENT source, not a replacement.
3. **Borrow nothing today.** Same license issue (GPL-3.0); same algorithmic-not-code-port discipline.

## Comparison table — four libraries reviewed

| Library | Stars | License | Status | Pattern code? | Verdict |
|---|---|---|---|---|---|
| white07S/TradingPatternScanner | 287 | CC BY-NC-SA 4.0 | Stable | Yes (low quality) | Reject |
| MarcosACH/chart-patterns | 2 | None | Stable | Pine Script | Reject |
| **BennyThadikaran/stock-pattern** | **373** | **GPL-3.0** | **Stable** | **Yes (high quality, 21 patterns)** | **Port (in progress)** |
| BennyThadikaran/precise-patterns | 17 | GPL-3.0 | Alpha WIP | No (only scaffolding) | Hold; re-eval later |

---

*Evaluated by: Claude (gh API + manual source inspection).*
