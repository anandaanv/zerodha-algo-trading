"""regime-record schema construction + validation."""
import pytest

from records.schema import (
    SCHEMA_VERSION,
    SCHEMA_VERIFIED,
    classify_regime,
    make_regime_record,
    validate_regime_record,
)


def _good():
    return make_regime_record(
        symbol="RELIANCE", date="2024-03-01", H=0.62, H_slope_20=-0.03,
        regime="trending", computed_at="2024-03-01T18:00:00Z",
        git_sha="deadbeef",
    )


def test_schema_version_and_unverified_flag():
    assert SCHEMA_VERSION == "regime-record-v1"
    # PR0 ships the schema flagged UNVERIFIED (no live record was discovered)
    assert SCHEMA_VERIFIED is False


def test_make_record_has_v1_shape():
    r = _good()
    assert r["schema"] == "regime-record-v1"
    assert r["symbol"] == "RELIANCE"
    assert r["timeframe"] == "1D"
    assert set(r["hurst"].keys()) == {"H", "window", "H_slope_20", "regime"}
    assert r["hurst"]["window"] == 250
    assert "computed_at" in r and "git_sha" in r


def test_validate_accepts_good_record():
    validate_regime_record(_good())  # should not raise


@pytest.mark.parametrize("mutate", [
    lambda r: r.update(schema="regime-record-v2"),
    lambda r: r.update(symbol=""),
    lambda r: r.pop("git_sha"),
    lambda r: r.__setitem__("hurst", {"H": 0.5}),
    lambda r: r["hurst"].update(regime="sideways"),
    lambda r: r["hurst"].update(H="not-a-number"),
    lambda r: r["hurst"].update(window=0),
])
def test_validate_rejects_bad_records(mutate):
    r = _good()
    mutate(r)
    with pytest.raises(ValueError):
        validate_regime_record(r)


def test_make_record_rejects_bad_regime():
    with pytest.raises(ValueError):
        make_regime_record(
            symbol="X", date="2024-01-01", H=0.5, H_slope_20=0.0,
            regime="sideways", computed_at="t", git_sha="s",
        )


def test_classify_regime_thresholds():
    assert classify_regime(0.70) == "trending"
    assert classify_regime(0.50) == "random"
    assert classify_regime(0.30) == "mean_revert"
