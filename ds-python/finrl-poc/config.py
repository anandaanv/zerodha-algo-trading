import os
from dotenv import load_dotenv

load_dotenv()

# Source DB (read-only candle data from main app)
SRC_DB_URL = os.getenv("SRC_DB_URL", "mysql+pymysql://anand:password@localhost:3306/algotrading")

# POC DB (isolated — write episode results and RL trades here)
POC_DB_URL = os.getenv("POC_DB_URL", "mysql+pymysql://anand:password@localhost:3306/finrl_poc")

# Symbols
NIFTY50 = [
    "RELIANCE", "TCS", "HDFCBANK", "BHARTIARTL", "ICICIBANK",
    "INFY", "SBIN", "BAJFINANCE", "HINDUNILVR", "ITC",
    "LT", "KOTAKBANK", "AXISBANK", "ASIANPAINT", "HCLTECH",
    "MARUTI", "SUNPHARMA", "TITAN", "ADANIENT", "NTPC",
]

# Training / eval split
TRAIN_START = "2020-04-01"
TRAIN_END   = "2024-12-31"
EVAL_START  = "2025-01-01"
EVAL_END    = "2026-04-10"

# Candle timeframe (matches enum in algotrading.candle)
TIMEFRAME = "Day"

# Per-timeframe windows (data availability drives these)
TF_CONFIG = {
    "Day": {
        "timeframe": "Day",
        "train_start": "2020-04-01", "train_end": "2024-12-31",
        "eval_start":  "2025-01-01", "eval_end":  "2026-04-10",
        "timesteps":    500_000,
        "window_size":  120,    # ~6 months of daily bars per episode
        "max_hold_bars": 20,    # force-close after 20 trading days (~1 month)
        "flat_penalty": 0.002,
    },
    "OneHour": {
        "timeframe": "OneHour",
        "train_start": "2024-09-27", "train_end": "2025-12-31",
        "eval_start":  "2026-01-01", "eval_end":  "2026-04-10",
        "timesteps":    800_000,
        "window_size":  400,    # ~60 trading days of 1h bars (6.5h/day)
        "max_hold_bars": 30,    # force-close after ~5 trading days
        "flat_penalty": 0.002,
    },
    "FifteenMinute": {
        "timeframe": "FifteenMinute",
        "train_start": "2025-04-01", "train_end": "2025-12-31",
        "eval_start":  "2026-01-01", "eval_end":  "2026-04-10",
        "timesteps":  1_000_000,
        "window_size":  1500,   # ~60 trading days of 15m bars (25 bars/day)
        "max_hold_bars": 50,    # force-close after ~2 trading days
        "flat_penalty": 0.002,
    },
}

# RL hyperparams
TIMESTEPS       = 500_000
INITIAL_CAPITAL = 1_000_000
TRANSACTION_COST_PCT = 0.001  # 0.1%
