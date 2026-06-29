"""regime-record schema.

SCHEMA PROVENANCE -- READ THIS.
==============================
This shape is UNVERIFIED against the live pipeline. PR0 schema-discovery queried
team algotrade (memory_list/keywords over tags ['regime-record'],
['ai-trader-scan-context','ai-trader-v2'], ['scan-context'], and an
indexable:false sweep) and found NO existing regime-record / scan-context bundle
to read a live shape from. Per the build plan's fallback, this schema mirrors the
phase-1 spec (slug fractal-reversal-phase-1-hurst) and is flagged UNVERIFIED.

LOCAL RECONCILIATION (do before any prod write): compare this against the CURRENT
live regime-record-v1 emitted by the pipeline. If they differ, follow the LIVE
shape and update this file. See the PR0 open-question memory tagged
['fractal-reversal-module','open-question'].

Spec shape (regime-record-v1):
    {
      "schema": "regime-record-v1",
      "symbol": "<SYM>",
      "date": "YYYY-MM-DD",
      "timeframe": "1D",
      "hurst": {
        "H": <float>,
        "window": 250,
        "H_slope_20": <float>,
        "regime": "trending" | "random" | "mean_revert"
      },
      "computed_at": "<iso8601>",
      "git_sha": "<sha>"
    }

NOTE: PR0 ships NO measures. The `hurst` block is part of the v1 contract but is
populated by the Hurst measure in PR2. `make_regime_record` accepts the hurst
fields so the schema can be exercised end-to-end (Fake adapters) with placeholder
values; it does not compute anything.
"""
from __future__ import annotations

from typing import Optional

SCHEMA_VERSION = "regime-record-v1"
SCHEMA_VERIFIED = False  # flipped True once reconciled against the live pipeline
DEFAULT_TIMEFRAME = "1D"
DEFAULT_HURST_WINDOW = 250
VALID_REGIMES = ("trending", "random", "mean_revert")


def make_regime_record(
    symbol: str,
    date: str,
    H: float,
    H_slope_20: float,
    regime: str,
    computed_at: str,
    git_sha: str,
    window: int = DEFAULT_HURST_WINDOW,
    timeframe: str = DEFAULT_TIMEFRAME,
) -> dict:
    """Build a regime-record-v1 dict. Validates before returning."""
    record = {
        "schema": SCHEMA_VERSION,
        "symbol": symbol,
        "date": date,
        "timeframe": timeframe,
        "hurst": {
            "H": H,
            "window": window,
            "H_slope_20": H_slope_20,
            "regime": regime,
        },
        "computed_at": computed_at,
        "git_sha": git_sha,
    }
    validate_regime_record(record)
    return record


def validate_regime_record(record: dict) -> None:
    """Raise ValueError if `record` does not satisfy the v1 contract."""
    if not isinstance(record, dict):
        raise ValueError("record must be a dict")

    if record.get("schema") != SCHEMA_VERSION:
        raise ValueError(
            f"schema must be {SCHEMA_VERSION!r}, got {record.get('schema')!r}"
        )

    for key in ("symbol", "date", "timeframe", "computed_at", "git_sha"):
        val = record.get(key)
        if not isinstance(val, str) or not val:
            raise ValueError(f"{key!r} must be a non-empty string")

    hurst = record.get("hurst")
    if not isinstance(hurst, dict):
        raise ValueError("'hurst' must be a dict")

    H = hurst.get("H")
    if not isinstance(H, (int, float)) or isinstance(H, bool):
        raise ValueError("hurst.H must be a number")

    window = hurst.get("window")
    if not isinstance(window, int) or window <= 0:
        raise ValueError("hurst.window must be a positive int")

    slope = hurst.get("H_slope_20")
    if not isinstance(slope, (int, float)) or isinstance(slope, bool):
        raise ValueError("hurst.H_slope_20 must be a number")

    regime = hurst.get("regime")
    if regime not in VALID_REGIMES:
        raise ValueError(f"hurst.regime must be one of {VALID_REGIMES}")


def classify_regime(H: float, threshold: float = 0.55) -> str:
    """Map a Hurst value to a regime label.

    Threshold 0.55 (not 0.50) corrects the DFA/R-S finite-sample upward bias
    per the phase-1 spec. Sign-blind: this is a tradability FILTER, not a
    direction call. Provided here so the label vocabulary lives with the schema;
    the actual H comes from the PR2 measure.
    """
    if H >= threshold:
        return "trending"
    if H <= (1.0 - threshold):
        return "mean_revert"
    return "random"
