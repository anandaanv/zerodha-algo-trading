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

    # EMA features
    ema20  = df["close"].ewm(span=20, adjust=False).mean()
    ema50  = df["close"].ewm(span=50, adjust=False).mean()
    ema100 = df["close"].ewm(span=100, adjust=False).mean()
    ema200 = df["close"].ewm(span=200, adjust=False).mean()
    df["ema20_dist"]   = (df["close"] - ema20)  / df["atr"]
    df["ema50_dist"]   = (df["close"] - ema50)  / df["atr"]
    df["ema100_dist"]  = (df["close"] - ema100) / df["atr"]
    df["ema200_dist"]  = (df["close"] - ema200) / df["atr"]
    df["ema_stack_bull"] = ((df["close"] > ema20) & (ema20 > ema50) & (ema50 > ema200)).astype(float)
    df["ema_stack_bear"] = ((df["close"] < ema20) & (ema20 < ema50) & (ema50 < ema200)).astype(float)

    # Stochastic
    stoch = ta.stoch(df["high"], df["low"], df["close"], k=14, d=3)
    df["stoch_k"] = stoch.iloc[:, 0]
    df["stoch_d"] = stoch.iloc[:, 1]

    # ADX / +DI / -DI
    adx_df = ta.adx(df["high"], df["low"], df["close"], length=14)
    df["adx"]      = adx_df.iloc[:, 0]
    df["plus_di"]  = adx_df.iloc[:, 1]
    df["minus_di"] = adx_df.iloc[:, 2]

    # BB width (volatility regime indicator)
    bb_low_col = [c for c in bb.columns if c.startswith("BBL")][0]
    bb_high_col = [c for c in bb.columns if c.startswith("BBU")][0]
    bb_mid_col = [c for c in bb.columns if c.startswith("BBM")][0]
    df["bb_width"] = (bb[bb_high_col] - bb[bb_low_col]) / bb[bb_mid_col]

    # Slopes (5-bar momentum)
    df["rsi_slope"]   = df["rsi"].diff(5)
    df["macd_slope"]  = df["macd_hist"].diff(5)
    df["adx_slope"]   = df["adx"].diff(5)
    df["ema20_slope"] = ema20.pct_change(5)

    # Pivot proxies: rolling extremes (in ATRs)
    df["recent_high_dist"]  = (df["close"] - df["high"].rolling(20).max()) / df["atr"]
    df["recent_low_dist"]   = (df["close"] - df["low"].rolling(20).min())  / df["atr"]
    df["recent_high50_dist"] = (df["close"] - df["high"].rolling(50).max()) / df["atr"]
    df["recent_low50_dist"]  = (df["close"] - df["low"].rolling(50).min())  / df["atr"]

    # Volume
    df["vol_zscore"] = (df["volume"] - df["volume"].rolling(20).mean()) / df["volume"].rolling(20).std()

    df = df.dropna()
    return df


FEATURE_COLS = [
    "close_pct", "rsi", "atr", "macd_hist", "bb_pct", "vol_ratio",
    "ema20_dist", "ema50_dist", "ema100_dist", "ema200_dist",
    "ema_stack_bull", "ema_stack_bear",
    "stoch_k", "stoch_d",
    "adx", "plus_di", "minus_di",
    "bb_width",
    "rsi_slope", "macd_slope", "adx_slope", "ema20_slope",
    "recent_high_dist", "recent_low_dist",
    "recent_high50_dist", "recent_low50_dist",
    "vol_zscore",
]
