"""
Technical indicator features computed on top of OHLCV data.
Uses pandas_ta — no TA-Lib native deps required.
"""
import pandas as pd
import pandas_ta as ta


def add_features(df: pd.DataFrame) -> pd.DataFrame:
    """
    Input:  DataFrame with columns [open, high, low, close, volume], datetime index
    Output: same DataFrame with added indicator columns, NaN rows dropped
    """
    df = df.copy()

    df["rsi"]       = ta.rsi(df["close"], length=14)
    df["atr"]       = ta.atr(df["high"], df["low"], df["close"], length=14)

    macd = ta.macd(df["close"], fast=12, slow=26, signal=9)
    df["macd_hist"] = macd["MACDh_12_26_9"]

    bb = ta.bbands(df["close"], length=20, std=2)
    bb_pct_col = [c for c in bb.columns if c.startswith("BBP")][0]
    df["bb_pct"]    = bb[bb_pct_col]     # 0–1 position within bands

    df["vol_ratio"] = df["volume"] / df["volume"].rolling(20).mean()

    # Normalise close to % change so price scale doesn't matter
    df["close_pct"] = df["close"].pct_change()

    df = df.dropna()
    return df


FEATURE_COLS = ["close_pct", "rsi", "atr", "macd_hist", "bb_pct", "vol_ratio"]
