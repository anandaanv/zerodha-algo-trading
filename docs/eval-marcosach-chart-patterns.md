# Evaluation: MarcosACH/chart-patterns

**Date:** 2026-05-09
**Branch:** feature/chart-pattern-eval
**Repo:** https://github.com/MarcosACH/chart-patterns
**Verdict:** **NOT ADOPTABLE** as code, but the conceptual model is sound.

---

## What it actually is
This is **NOT a code library**. It is a single **Pine Script** file (`chart_patterns.pinescript`) intended to run inside TradingView's chart engine as an indicator. There is no installable package, no API, no bindings to any general-purpose language.

## Stats
- 2 stars, single contributor
- No license (default = all rights reserved — cannot legally redistribute / use without permission)
- Pine Script only (TradingView-specific DSL, cannot run on JVM)
- Two files total: README.md + chart_patterns.pinescript

## Patterns detected
- Lower Low (LL)
- Lower Low + Lower High (LL & LH)
- Higher High (HH)
- Higher High + Higher Low (HH & HL)
- Double Top (with Active / Confirmed / Invalid state)
- Double Bottom (with Active / Confirmed / Invalid state)

## Why this is not adoptable as code
1. Pine Script runs only inside TradingView. It cannot be embedded into our Spring Boot backend.
2. No license = legally unusable beyond personal viewing.
3. Pattern set is a strict subset of what we already detect (HH/HL/LH/LL via regime tracking + DTB via DtbCandidateDetector).

## What IS valuable — the conceptual model
The README spells out a clean state machine for Double Tops / Double Bottoms that is worth verifying we implement:

**State machine:**
- **Active:** two peaks/troughs identified + neckline pivot, but price has not yet broken neckline
- **Confirmed:** price decisively closes beyond neckline (below for DT, above for DB)
- **Invalid:** before neckline break, a new pivot forms that negates structural integrity

**The invalidation rule is the interesting bit:**
> If a new pivot low forms ABOVE OR AT the neckline of an active Double Top before breakout, the pattern is invalidated. Logic: market failed to break support, established a higher low → bearish thesis dead.

Same rule mirrored for Double Bottom.

This three-state lifecycle (Active → Confirmed | Invalid) is a sensible way to filter out patterns that "fall apart" before confirmation rather than treating them as valid signals that simply didn't trigger.

## Recommended action
1. **Close this evaluation** — do not import any code.
2. **Audit our DtbCandidateDetector / DtbExitStrategy** to verify the Active/Confirmed/Invalid lifecycle exists. If it doesn't, add it. The invalidation rule (new pivot forming above neckline before breakout = mark invalid) prevents trading patterns that have already failed structurally.
3. **No new dependency needed** — this is a small enhancement to existing pivot-based DTB detection.

---

*Evaluated by: Claude (with verification of source via gh API)*
