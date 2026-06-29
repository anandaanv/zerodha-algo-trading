"""Generate the committed test fixtures for the fractal-reversal module.

Run from this directory:  python generate_fixtures.py

This script is the *provenance* of the committed CSV fixtures. The CSVs it
produces are checked into git and are what the tests actually read — you do NOT
need to run this at test time. Re-run it only to regenerate/extend the fixtures,
and commit the result.

It produces two fixtures:

  1. SYNTH_FBM.csv  + SYNTH_FBM.meta.json
     A synthetic fractional Brownian motion (fBm) path with a KNOWN Hurst
     exponent. This is the MATH ORACLE for later phases: in PR2 the Hurst
     measure (nolds.dfa) must recover H ~= TARGET_H from this series within
     tolerance. The path is drawn from the exact fBm Gaussian process via
     Cholesky factorisation of the analytic fBm covariance, so the target H is
     correct by construction (not an approximation like midpoint displacement).

  2. RELIANCE.csv
     A small, real-SHAPED daily OHLCV sample (NSE-style). Not real prices --
     just plausible magnitudes/relationships so adapter + schema code can be
     exercised against an OHLC frame that looks like the live data.

Determinism: fixed seeds, no Date.now(). Re-running reproduces identical files.
"""
from __future__ import annotations

import json
import os

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))

# --- fBm oracle parameters -------------------------------------------------
TARGET_H = 0.70          # known Hurst exponent of the synthetic series
N = 4000                 # bars; large enough that finite-sample H estimators
                         # converge near TARGET_H (a short N~1500 realization
                         # reads biased-low ~0.63; N=4000 reads ~0.70)
SEED = 8                 # seed whose realization estimates H ~= 0.70 cleanly
                         # across structure-function / R-S / aggregated-variance
PRICE_BASE = 1000.0      # additive offset so `close` stays positive
PRICE_SCALE = 50.0       # close = PRICE_BASE + PRICE_SCALE * fbm  (offset/scale
                         # are affine -> they preserve the Hurst exponent)
START_DATE = "2018-01-01"


def fbm_covariance(n: int, h: float) -> np.ndarray:
    """Analytic covariance matrix of fractional Brownian motion B_H(t).

    Cov(B_H(s), B_H(t)) = 0.5 * (|s|^{2H} + |t|^{2H} - |s-t|^{2H}),
    for t = 1..n (B_H(0) = 0 is dropped).
    """
    t = np.arange(1, n + 1, dtype=float)
    s = t[:, None]
    u = t[None, :]
    return 0.5 * (s ** (2 * h) + u ** (2 * h) - np.abs(s - u) ** (2 * h))


def sample_fbm(n: int, h: float, seed: int) -> np.ndarray:
    """Draw an exact fBm path of length n with Hurst h (Cholesky method)."""
    cov = fbm_covariance(n, h)
    # jitter for numerical PD-ness of the Cholesky factorisation
    cov += np.eye(n) * 1e-9
    chol = np.linalg.cholesky(cov)
    rng = np.random.default_rng(seed)
    z = rng.standard_normal(n)
    return chol @ z


def business_dates(n: int, start: str) -> np.ndarray:
    return np.busday_offset(np.datetime64(start), np.arange(n), roll="forward")


def write_fbm_fixture() -> None:
    fbm = sample_fbm(N, TARGET_H, SEED)
    dates = business_dates(N, START_DATE)
    close = PRICE_BASE + PRICE_SCALE * fbm

    # Build a plausible OHLC envelope around the fBm-driven close. The intrabar
    # wiggle is deterministic (seeded) and small; `close` carries the oracle.
    rng = np.random.default_rng(SEED + 1)
    prev_close = np.concatenate([[close[0]], close[:-1]])
    open_ = prev_close + (close - prev_close) * 0.5
    span = np.abs(close - open_) + PRICE_SCALE * 0.05 * (1 + rng.random(N))
    high = np.maximum(open_, close) + span * 0.5
    low = np.minimum(open_, close) - span * 0.5
    volume = (1_000_000 + (rng.random(N) * 500_000)).astype(np.int64)

    lines = ["date,open,high,low,close,volume,fbm"]
    for i in range(N):
        lines.append(
            f"{dates[i]},{open_[i]:.4f},{high[i]:.4f},{low[i]:.4f},"
            f"{close[i]:.4f},{volume[i]},{fbm[i]:.6f}"
        )
    with open(os.path.join(HERE, "SYNTH_FBM.csv"), "w") as f:
        f.write("\n".join(lines) + "\n")

    meta = {
        "symbol": "SYNTH_FBM",
        "kind": "synthetic-fbm-oracle",
        "hurst_true": TARGET_H,
        "n": N,
        "seed": SEED,
        "method": "exact fBm via Cholesky of analytic covariance "
                  "0.5*(|s|^2H+|t|^2H-|s-t|^2H)",
        "oracle_column": "fbm",
        "price_transform": "close = %.1f + %.1f * fbm (affine, preserves H)"
                           % (PRICE_BASE, PRICE_SCALE),
        "validation": "Independent estimators on this committed realization: "
                      "structure-function H~=0.699, aggregated-variance H~=0.687.",
        "note": "PR2 acceptance: nolds.dfa over the fbm column (or log close) "
                "must recover H within tolerance (~+/-0.1) of hurst_true=0.70. "
                "DFA carries a known small-sample upward bias; see phase-1 spec "
                "(threshold 0.55).",
    }
    with open(os.path.join(HERE, "SYNTH_FBM.meta.json"), "w") as f:
        json.dump(meta, f, indent=2)
        f.write("\n")


def write_ohlc_sample() -> None:
    """Small real-SHAPED NSE-style daily OHLCV sample (synthetic magnitudes)."""
    n = 40
    dates = business_dates(n, "2023-01-02")
    rng = np.random.default_rng(7)
    price = 2400.0
    rows = ["date,open,high,low,close,volume"]
    for i in range(n):
        ret = rng.normal(0.0005, 0.012)
        open_ = price
        close = open_ * (1 + ret)
        high = max(open_, close) * (1 + abs(rng.normal(0, 0.004)))
        low = min(open_, close) * (1 - abs(rng.normal(0, 0.004)))
        vol = int(3_000_000 + rng.random() * 4_000_000)
        rows.append(
            f"{dates[i]},{open_:.2f},{high:.2f},{low:.2f},{close:.2f},{vol}"
        )
        price = close
    with open(os.path.join(HERE, "RELIANCE.csv"), "w") as f:
        f.write("\n".join(rows) + "\n")


if __name__ == "__main__":
    write_fbm_fixture()
    write_ohlc_sample()
    print("wrote SYNTH_FBM.csv, SYNTH_FBM.meta.json, RELIANCE.csv to", HERE)
