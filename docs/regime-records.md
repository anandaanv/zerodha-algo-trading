# Regime Record Pipeline — algotrade side

This document describes the three Spring components on the algotrade side of the watchlist→regime pipeline, and the memsys tag conventions they rely on.

**Canonical schema:** memsys memory `a44c035a-8c14-4425-832a-4e006b9b2c83` (`regime-record-v1`). If the schema memory updates and this doc diverges, the schema memory wins — re-read it and align this doc.

## The seam

Haiku/Claude NEVER places trades. It writes **regime records** — advisory statements of what state a stock is in. algotrade reads regime records and applies its own entry logic, sizing, and risk caps. The LLM says "stock X is in regime Y, bias Z, bounded by levels L, valid until T." The verb _enter_ belongs entirely to algotrade. Risk/sizing/kill-switch live ONLY in algotrade — `conviction` is advisory, never a sizing instruction.

## Three components

| # | Component | When | What |
|---|---|---|---|
| 1 | `NightlyQueueSeeder` | Before Haiku runs (default 18:00 IST) | Writes one `pending` queue marker per F&O underlying. The Haiku worker drains this. |
| 2 | `RegimeRecordReader` | Pre-market each morning | Reads + validates today's regime records, drops expired ones, exposes the validated watchlist. |
| 3 | `GateEvalLoop` | After-market each evening (default 19:00 IST) | Scores N-day-old records against subsequent price action, writes aggregate hit-rate by `(regime × conviction)`. |

All three are scheduled (`@Scheduled`) AND callable manually via REST.

## REST endpoints (manual fire)

```
POST /api/ai-trader-v2/queue-seeder/run?dateLabel=YYYY-MM-DD
GET  /api/ai-trader-v2/regime-reader/read?dateLabel=YYYY-MM-DD&sourceRun=nightly|intraday
POST /api/ai-trader-v2/gate-eval/run?dateLabel=YYYY-MM-DD
```

All require JWT auth (`/api/auth/login` first). Date label defaults to today (IST).

## Memsys tag conventions

These four tag schemes are the contract between Haiku and algotrade. Stay in sync with the schema memory.

### Scan queue (Component 1 writes, Haiku worker drains)
```
tags = ["ai-trader-v2", "scan-queue", "date-YYYY-MM-DD", "status-pending", "symbol-{SYM}"]
type = note
content = "{SYM} queued"
ttl_seconds = 108000   # 30 hours — fresh queue each night
```
When the Haiku worker has scored a symbol, it flips this to `status-done` and may delete the pending memory. The seeder treats absence-of-pending as "either not started OR already drained" — the search-by-tags idempotency check covers both.

### Regime record (Haiku writes, Component 2 reads)
```
tags = ["ai-trader-v2", "regime-record", "watchlist", "symbol-{SYM}", "date-YYYY-MM-DD", "source-{nightly|intraday}"]
type = decision  # (or "note" — schema doesn't fix this; reader doesn't filter by type)
content = JSON conforming to regime-record-v1 (see schema memory a44c035a)
ttl_seconds = 6 days  # must outlive horizon_days for the eval loop
```
Records that fail strict validation are REJECTED — never used for trading. Records with `valid_until ≤ now` are DROPPED.

### Gate eval aggregate (Component 3 writes)
```
tags = ["ai-trader-v2", "gate-eval", "date-YYYY-MM-DD"]
type = fact
content = markdown table with per-regime × per-conviction hit-rate + per-record audit
```

## Validation rules (RegimeRecordValidator)

A regime record MUST have:

- `schema == "regime-record-v1"`
- `symbol` (non-blank)
- `as_of` (ISO with TZ)
- `regime ∈ { trending_up, trending_down, ranging, squeeze_coiled, reversal_setup, breakout_pending }`
- `bias ∈ { long, short, neutral }` — **NEUTRAL is valid ONLY with `regime=squeeze_coiled`**
- `conviction ∈ { low, normal, high }` — ADVISORY ONLY, never overrides risk caps
- `valid_until` (ISO with TZ)
- `defining_levels.invalidation` (number) — REQUIRED, kills the regime read
- `alignment` (object)
- `trigger_to_watch` (non-blank prose — condition description, NOT an order)

`source_run`, when present, must be `nightly` or `intraday`.

Optional fields: `horizon_days`, `notes`, `defining_levels.pivot/support/resistance/targets_if_resolves`, `alignment.stories_agreeing/stories_conflicting/agreement_note`.

## Eval scoring rules

For each record (Component 3):

| Outcome | Long bias | Short bias |
|---|---|---|
| HIT | high ≥ any `target_if_resolves[i]` (before invalidation) | low ≤ any target (before invalidation) |
| INVALIDATED | low ≤ `invalidation` | high ≥ `invalidation` |
| PENDING | neither, and `valid_until > now` | same |
| MISS | neither, and `valid_until ≤ now` | same |
| SKIPPED_NEUTRAL | bias=neutral (squeeze_coiled) | — bidirectional eval not implemented in v1 |

Invalidation is checked **before** target on the same bar (conservative — if both could be true the regime is treated as INVALIDATED rather than HIT).

Aggregation: group by `(regime_class, conviction)`. Hit rate = `hit / (hit + miss + invalidated)`. Pending and skipped_neutral excluded from the denominator.

## Risk-layer boundary (read this before wiring entries)

The reader returns `RegimeRecord` objects. **The strategy layer that consumes them MUST**:

- Apply its OWN entry rule. Don't treat `trigger_to_watch` as code — it's prose describing the condition.
- Apply its OWN position sizing. Don't let `conviction` set size beyond hard caps. Conviction MAY scale within bounds; it MUST NOT scale beyond.
- Apply its OWN risk caps: max concurrent positions, daily-loss kill-switch, per-trade risk %, correlation cap.
- Respect `valid_until` — re-confirm or discard past it.
- Treat `targets_if_resolves` / `invalidation` as **structural reference levels**, not orders. Build your own entry, stop, target from them.

## Out of scope here

The live-market execution engine, order placement, and the risk/kill-switch layer are NOT in this module. This pipeline is **queue → read → eval** scaffolding only. The strategy/execution layer is a separate spec.

## File map

```
src/main/java/com/dtech/aitrader/v2/regime/
├── RegimeRecord.java               # DTO matching regime-record-v1
├── RegimeClass.java                # enum
├── Bias.java                       # enum
├── Conviction.java                 # enum
├── DefiningLevels.java             # nested DTO
├── Alignment.java                  # nested DTO
├── RegimeRecordValidator.java      # strict schema + cross-field validator
├── queue/NightlyQueueSeeder.java   # Component 1
├── reader/RegimeRecordReader.java  # Component 2
└── eval/GateEvalLoop.java          # Component 3
```

## Config (application.properties)

```
# Component 1 — queue seeder
regime.queue.enabled=false           # set true in prod
regime.queue.cron=0 0 18 * * MON-FRI  # 18:00 IST weekdays
regime.queue.user-id=1
regime.queue.ttl-seconds=108000

# Component 2 — reader (no scheduled fire; called by strategy layer)
regime.reader.user-id=1
regime.reader.max-records=500

# Component 3 — eval
regime.eval.enabled=false
regime.eval.cron=0 0 19 * * MON-FRI   # 19:00 IST weekdays
regime.eval.user-id=1
regime.eval.lookback-days=3
regime.eval.max-records=500
```
