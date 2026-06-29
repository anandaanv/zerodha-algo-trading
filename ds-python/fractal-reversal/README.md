# fractal-reversal

A regime + reversal **feature layer** for AI Trader v2 (not a price predictor).
It computes scale-resolved persistence, disorder, and phase from daily OHLC and
emits regime records that **gate and size** the existing direction engine
(Elliott / structure). It never replaces that engine. Per Nifty-50 name per day
it aims to answer: *is this tradable* (regime), *what horizon is turning*
(wavelet band), *which way* (Hilbert phase), *how confident* (multifractal /
rollover).

Design specs live in memsys team `algotrade`
(`memory_list(tags=["fractal-reversal-module","spec"])`).

> **This is PR0 — the scaffold only.** No measure (Hurst / wavelet / Hilbert /
> entropy) is implemented yet. Fixture tests prove the *plumbing and math
> harness are correct*; they do **not** prove any trading edge exists. Signal is
> a LOCAL question, answered by the Phase-0 kill-gate on real NSE data.

## Layout

```
fractal-reversal/
  adapters/        DataSource (Fake/Kite) + RecordSink (Fake/Memsys)
  harness/         Phase-0 validation harness        (EMPTY in PR0 → PR1)
  measures/        Hurst/wavelet/hilbert/entropy      (EMPTY in PR0 → PR2+)
  records/         schema.py (regime-record-v1)
  data/fixtures/   committed oracle + sample CSVs
  data/ohlc/       local Kite parquet cache (gitignored)
  reports/         Phase-0 reports (gitignored)
  tests/           pytest, fixtures only, creds-free
  run_pipeline.py  end-to-end wiring (no measures yet)
  requirements.txt pinned deps
```

## Quickstart (creds-free)

```bash
cd ds-python/fractal-reversal
pip install -r requirements.txt
pytest                       # green with zero credentials (fake adapters)
python run_pipeline.py --symbols SYNTH_FBM RELIANCE
```

## Configuration (env)

| var | values | default | notes |
|-----|--------|---------|-------|
| `FRM_DATASOURCE` | `fake` \| `kite` | `fake` | `kite` is a stub until wired locally |
| `FRM_SINK` | `fake` \| `memsys_scratch` \| `memsys_prod` | `fake` | `memsys_prod` is local-only and gated |
| `FRM_ALLOW_PROD_WRITES` | `1` | unset | required (locally, not in CI) to enable prod memsys writes |

### Adapters

- **FakeDataSource** — reads `data/fixtures/{SYMBOL}.csv`. Deterministic.
- **KiteDataSource** — real shape, **stubbed**: the cloud build has no Kite
  access. Raises `NotImplementedError` until wired locally (creds +
  instrument-token lookup + **split/bonus adjustment**). See its docstring.
- **FakeSink** — appends JSONL to `records/out/`.
- **MemsysSink** — real memsys writer, **write-gated**. Scratch mode writes only
  to `fractal-reversal-module / scratch-build-test` tags (safe in CI). Prod mode
  (`ai-trader-v2 / regime-record`) is hard-blocked in CI and refuses to run
  unless `FRM_ALLOW_PROD_WRITES=1` in a local run. Fixture-derived records must
  never pollute the live pipeline.

## ⚠️ Schema status: UNVERIFIED

`records/schema.py` implements `regime-record-v1` from the **phase-1 spec**.
PR0 schema-discovery queried team `algotrade` for an existing regime-record /
scan-context bundle to match the *live* shape and **found none**, so this schema
is the spec fallback and is flagged `SCHEMA_VERIFIED = False`. The diff is filed
as an open-question memory (`["fractal-reversal-module","open-question"]`).
**Reconcile against the live `regime-record-v1` before any prod write.**

```json
{
  "schema": "regime-record-v1",
  "symbol": "RELIANCE", "date": "2024-03-01", "timeframe": "1D",
  "hurst": {"H": 0.62, "window": 250, "H_slope_20": -0.03, "regime": "trending"},
  "computed_at": "<iso8601>", "git_sha": "<sha>"
}
```

## Non-negotiable rules (from the spec)

- **R1 Causal only** — every measure uses data ≤ t. No future peeking.
- **R2 Harness before measures** — Phase-0 leakage self-test first (PR1).
- **R3 OOS kill-gate** — no measure joins the production vector until it passes
  Phase-0 forward-return separation on held-out real data, net of costs.
- **R4 One measure per PR** — no batching phases.
- **R5 Characterize, don't extrapolate** — no fit-then-project-price code.

## LOCAL RUN (handoff)

Fixtures prove correctness; **signal is decided here, on real data.**

1. `FRM_DATASOURCE=kite` + Kite creds → ingest ≥5y split/bonus-adjusted daily
   OHLC for Nifty-50 to `data/ohlc/*.parquet`.
2. Reconcile `records/schema.py` against the live `regime-record-v1`; resolve the
   PR0 open-question and set `SCHEMA_VERIFIED = True`.
3. `FRM_SINK=memsys_prod` + `FRM_ALLOW_PROD_WRITES=1` + memsys creds.
4. `python run_pipeline.py` over a date range (once measures exist, PR2+).
5. Run the Phase-0 harness on real Nifty-50 history; read `reports/`. **The
   kill-gate verdict comes from this run, not from fixtures.**

## Build sequence

PR0 scaffold *(this)* → PR1 Phase-0 harness → PR2 Hurst → PR3 wavelet →
PR4 Hilbert phase → PR5 combine survivors. One measure per PR; stop for human
review between phases.
