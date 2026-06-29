"""DataSource adapter boundary.

A DataSource yields split/bonus-adjusted DAILY OHLCV for a symbol over a date
range. Two implementations:

  - FakeDataSource  : reads committed CSV fixtures (creds-free; used by CI/tests
                      and any offline correctness work).
  - KiteDataSource  : REAL shape, but STUBBED -- the cloud build agent has no
                      Kite access, so the live pull is wired locally by the user.

Canonical return contract (the rest of the module depends on exactly this):
    pandas.DataFrame with columns
        ['date', 'open', 'high', 'low', 'close', 'volume']
    - 'date'  : datetime64[ns], ascending, unique
    - o/h/l/c : float
    - volume  : int/float
The spec's shorthand DataFrame[date,o,h,l,c,v] maps onto these full names.
"""
from __future__ import annotations

import os
from abc import ABC, abstractmethod
from typing import Optional

import pandas as pd

OHLCV_COLUMNS = ["date", "open", "high", "low", "close", "volume"]


class DataSource(ABC):
    """Interface: causal daily OHLCV provider."""

    @abstractmethod
    def get_daily_ohlc(
        self,
        symbol: str,
        start: Optional[str] = None,
        end: Optional[str] = None,
    ) -> pd.DataFrame:
        """Return daily OHLCV for `symbol` within [start, end] (inclusive).

        `start`/`end` are 'YYYY-MM-DD' strings or None (no bound on that side).
        Result columns == OHLCV_COLUMNS, ascending unique dates.
        """
        raise NotImplementedError


class FakeDataSource(DataSource):
    """Reads `data/fixtures/{SYMBOL}.csv`. Deterministic, creds-free.

    Fixtures shipped: SYNTH_FBM (the known-Hurst fBm math oracle) and RELIANCE
    (a small real-shaped NSE-style sample). The SYNTH_FBM csv carries an extra
    `fbm` column (the raw oracle series); it is dropped from the OHLCV contract
    here -- read it directly from the csv when you need the oracle.
    """

    def __init__(self, fixtures_dir: Optional[str] = None):
        if fixtures_dir is None:
            fixtures_dir = os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                "data",
                "fixtures",
            )
        self.fixtures_dir = fixtures_dir

    def _path(self, symbol: str) -> str:
        return os.path.join(self.fixtures_dir, f"{symbol}.csv")

    def get_daily_ohlc(
        self,
        symbol: str,
        start: Optional[str] = None,
        end: Optional[str] = None,
    ) -> pd.DataFrame:
        path = self._path(symbol)
        if not os.path.exists(path):
            raise FileNotFoundError(
                f"No fixture for symbol {symbol!r} at {path}. "
                f"Available fixtures live in {self.fixtures_dir}."
            )
        df = pd.read_csv(path)
        df["date"] = pd.to_datetime(df["date"])
        df = df[OHLCV_COLUMNS].copy()
        if start is not None:
            df = df[df["date"] >= pd.to_datetime(start)]
        if end is not None:
            df = df[df["date"] <= pd.to_datetime(end)]
        df = df.sort_values("date").drop_duplicates("date").reset_index(drop=True)
        return df


class KiteDataSource(DataSource):
    """REAL Zerodha Kite implementation -- STUBBED for the cloud build.

    The cloud agent has no Kite access (Kite lives on the user's local machine,
    currently gated by a `team.read_memory` permission bug). This class encodes
    the *expected call shape* so the local run is a fill-in-the-blanks job, and
    refuses to run until wired. Do NOT silently return fake data here -- a stub
    that fabricates OHLC is worse than one that raises.

    LOCAL WIRING (TODO for the user):
      1. pip install kiteconnect
      2. creds from env: KITE_API_KEY, KITE_ACCESS_TOKEN
      3. resolve `symbol` -> instrument_token via the NSE instruments dump
         (kite.instruments("NSE")); cache the mapping.
      4. kite.historical_data(
             instrument_token, from_date, to_date, interval="day")
         -> list[dict(date, open, high, low, close, volume)] -> DataFrame.

    SPLIT/BONUS ADJUSTMENT (critical, do not skip):
      Kite's historical_data is NOT back-adjusted for splits/bonuses. Raw series
      have artificial gaps on ex-dates that corrupt Hurst / wavelet / phase
      measures. Either request adjusted data where available, or apply corporate
      -action adjustment factors before returning. Document the choice; an
      unadjusted series will silently poison every downstream measure.
    """

    def __init__(
        self,
        api_key: Optional[str] = None,
        access_token: Optional[str] = None,
        kite_client: Optional[object] = None,
    ):
        self.api_key = api_key or os.environ.get("KITE_API_KEY")
        self.access_token = access_token or os.environ.get("KITE_ACCESS_TOKEN")
        # Inject a pre-built client locally; None in the cloud build.
        self._kite = kite_client

    def get_daily_ohlc(
        self,
        symbol: str,
        start: Optional[str] = None,
        end: Optional[str] = None,
    ) -> pd.DataFrame:
        raise NotImplementedError(
            "KiteDataSource is a stub: the cloud build has no Kite access. "
            "Wire it locally (creds + instrument-token lookup + split/bonus "
            "adjustment) -- see the class docstring. Until then use "
            "FRM_DATASOURCE=fake."
        )
