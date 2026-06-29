# Test fixtures

Committed, deterministic, credential-free. These prove **correctness**, not
signal. Regenerate with `python generate_fixtures.py` (see that script for the
exact construction and seeds).

## `SYNTH_FBM.csv` (+ `SYNTH_FBM.meta.json`) — the math oracle

A synthetic fractional Brownian motion path with a **known Hurst exponent
H = 0.70**, drawn from the *exact* fBm Gaussian process via Cholesky
factorisation of the analytic covariance
`0.5*(|s|^2H + |t|^2H - |s-t|^2H)`. The target H is correct by construction.

| column | meaning |
|--------|---------|
| `date` | business-day index |
| `open/high/low/close` | OHLC envelope; `close = 1000 + 50*fbm` (affine → preserves H) |
| `volume` | synthetic |
| `fbm` | **the raw oracle series** — use this (or `log(close)`) to validate a Hurst estimator |

`SYNTH_FBM.meta.json` records `hurst_true`, `n`, `seed`, the method, and the
empirical reads of independent estimators on the committed realisation
(structure-function ≈ 0.699, aggregated-variance ≈ 0.687).

**Used as the oracle in PR2:** `nolds.dfa` over the `fbm` column must recover
H within tolerance (~±0.1) of 0.70. DFA carries a known small-sample upward
bias — see the phase-1 spec (threshold 0.55, not 0.50).

`FakeDataSource` returns only the canonical OHLCV columns and **drops `fbm`**;
read the oracle column straight from the CSV when you need it.

## `RELIANCE.csv` — real-shaped sample

~40 rows of NSE-style daily OHLCV. **Synthetic magnitudes**, not real prices —
just plausible shapes/relationships so adapter and schema code can run against
an OHLC frame resembling the live data. OHLC invariants hold
(`high ≥ open/close ≥ low`, positive volume).
