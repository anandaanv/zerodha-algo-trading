"""DataSource adapter contract (Fake reads fixtures; Kite is a stub)."""
import pytest

from adapters.datasource import (
    OHLCV_COLUMNS,
    DataSource,
    FakeDataSource,
    KiteDataSource,
)
from config import get_datasource


def test_fake_returns_canonical_ohlcv():
    ds = FakeDataSource()
    df = ds.get_daily_ohlc("RELIANCE")
    assert list(df.columns) == OHLCV_COLUMNS
    assert len(df) > 0
    assert df["date"].is_monotonic_increasing
    assert str(df["date"].dtype).startswith("datetime64")


def test_fake_synth_fbm_drops_oracle_column_from_contract():
    df = FakeDataSource().get_daily_ohlc("SYNTH_FBM")
    # the OHLCV contract must not leak the raw oracle column
    assert list(df.columns) == OHLCV_COLUMNS
    assert "fbm" not in df.columns
    assert len(df) >= 1000


def test_fake_date_filtering():
    ds = FakeDataSource()
    full = ds.get_daily_ohlc("SYNTH_FBM")
    mid = full["date"].iloc[len(full) // 2]
    sub = ds.get_daily_ohlc("SYNTH_FBM", start=str(mid.date()))
    assert sub["date"].min() >= mid
    assert len(sub) < len(full)


def test_fake_unknown_symbol_raises():
    with pytest.raises(FileNotFoundError):
        FakeDataSource().get_daily_ohlc("NO_SUCH_SYMBOL")


def test_kite_is_a_stub():
    ds = KiteDataSource()
    assert isinstance(ds, DataSource)
    with pytest.raises(NotImplementedError):
        ds.get_daily_ohlc("RELIANCE")


def test_default_datasource_is_fake():
    assert isinstance(get_datasource(), FakeDataSource)


def test_datasource_factory_rejects_unknown():
    with pytest.raises(ValueError):
        get_datasource("bogus")
