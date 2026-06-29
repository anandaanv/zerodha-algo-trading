"""Fixtures are present, well-shaped, and the fBm oracle is documented."""
import json
import os

import pandas as pd

FIX = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "data", "fixtures",
)


def test_synth_fbm_csv_shape():
    df = pd.read_csv(os.path.join(FIX, "SYNTH_FBM.csv"))
    for col in ["date", "open", "high", "low", "close", "volume", "fbm"]:
        assert col in df.columns
    assert len(df) >= 1000  # comfortably > 250-bar rolling Hurst window
    dates = pd.to_datetime(df["date"])
    assert dates.is_monotonic_increasing
    assert dates.is_unique


def test_synth_fbm_meta_records_known_hurst():
    with open(os.path.join(FIX, "SYNTH_FBM.meta.json")) as f:
        meta = json.load(f)
    assert meta["hurst_true"] == 0.70
    assert meta["oracle_column"] == "fbm"
    assert "Cholesky" in meta["method"]
    # the oracle column referenced in meta actually exists
    df = pd.read_csv(os.path.join(FIX, "SYNTH_FBM.csv"))
    assert meta["oracle_column"] in df.columns
    assert df["fbm"].notna().all()


def test_reliance_sample_is_valid_ohlc():
    df = pd.read_csv(os.path.join(FIX, "RELIANCE.csv"))
    for col in ["date", "open", "high", "low", "close", "volume"]:
        assert col in df.columns
    assert len(df) > 0
    # OHLC invariants
    assert (df["high"] >= df["low"]).all()
    assert (df["high"] >= df["open"]).all()
    assert (df["high"] >= df["close"]).all()
    assert (df["low"] <= df["open"]).all()
    assert (df["low"] <= df["close"]).all()
    assert (df["volume"] > 0).all()
