"""
Load OHLCV candle data from algotrading DB for given symbols and timeframe.
Reads cross-DB from algotrading.candle + algotrading.instrument (read-only).
"""
import pandas as pd
from sqlalchemy import create_engine, text
from config import SRC_DB_URL, TIMEFRAME


_engine = None

def _get_engine():
    global _engine
    if _engine is None:
        _engine = create_engine(SRC_DB_URL, pool_pre_ping=True)
    return _engine


def load_candles(symbols: list[str], start: str, end: str, timeframe: str = TIMEFRAME) -> pd.DataFrame:
    """
    Returns DataFrame with columns: datetime, symbol, open, high, low, close, volume
    Sorted by datetime ASC.
    """
    engine = _get_engine()
    placeholders = ", ".join(f":sym{i}" for i in range(len(symbols)))
    params = {f"sym{i}": s for i, s in enumerate(symbols)}
    params["tf"] = timeframe
    params["start"] = start
    params["end"] = end

    query = text(f"""
        SELECT
            c.timestamp   AS datetime,
            i.tradingsymbol AS symbol,
            c.open, c.high, c.low, c.close, c.volume
        FROM candle c
        JOIN instrument i ON i.instrument_token = c.instrument_instrument_token
        WHERE i.tradingsymbol IN ({placeholders})
          AND i.exchange = 'NSE'
          AND c.timeframe = :tf
          AND c.timestamp BETWEEN :start AND :end
        ORDER BY c.timestamp ASC
    """)

    with engine.connect() as conn:
        df = pd.read_sql(query, conn, params=params)

    df["datetime"] = pd.to_datetime(df["datetime"])
    return df


def load_single(symbol: str, start: str, end: str, timeframe: str = TIMEFRAME) -> pd.DataFrame:
    """Load candles for a single symbol, returns indexed by datetime."""
    df = load_candles([symbol], start, end, timeframe)
    df = df.set_index("datetime").drop(columns=["symbol"])
    return df
