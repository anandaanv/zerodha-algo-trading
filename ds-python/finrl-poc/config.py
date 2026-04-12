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
TRAIN_START = "2021-01-01"
TRAIN_END   = "2024-06-30"
EVAL_START  = "2024-07-01"
EVAL_END    = "2025-08-31"

# Candle timeframe (matches enum in algotrading.candle)
TIMEFRAME = "OneHour"

# RL hyperparams
TIMESTEPS       = 500_000
INITIAL_CAPITAL = 1_000_000
TRANSACTION_COST_PCT = 0.001  # 0.1%
