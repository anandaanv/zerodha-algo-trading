"""RecordSink contract, with focus on the MemsysSink write-gate."""
import json
import os

import pytest

from adapters.sink import (
    PROD_BASE_TAGS,
    SCRATCH_BASE_TAGS,
    FakeSink,
    MemsysSink,
    ProductionWriteBlocked,
)
from config import get_sink
from records.schema import make_regime_record


def _rec():
    return make_regime_record(
        symbol="SYNTH_FBM", date="2020-01-01", H=0.7, H_slope_20=0.01,
        regime="trending", computed_at="2020-01-01T00:00:00Z", git_sha="abc123",
    )


class _CollectingClient:
    def __init__(self):
        self.writes = []

    def memory_write(self, content, tags, indexable):
        self.writes.append({"content": content, "tags": tags,
                            "indexable": indexable})


def test_fake_sink_roundtrip(tmp_path):
    out = tmp_path / "records.jsonl"
    sink = FakeSink(out_path=str(out))
    sink.write_regime_record(_rec())
    sink.write_regime_record(_rec())
    lines = out.read_text().strip().splitlines()
    assert len(lines) == 2
    assert json.loads(lines[0])["symbol"] == "SYNTH_FBM"


def test_default_sink_is_fake():
    assert isinstance(get_sink(), FakeSink)


def test_scratch_write_uses_scratch_tags_never_prod():
    client = _CollectingClient()
    sink = MemsysSink(mode="scratch", client=client)
    sink.write_regime_record(_rec())
    assert len(client.writes) == 1
    tags = client.writes[0]["tags"]
    for t in SCRATCH_BASE_TAGS:
        assert t in tags
    for t in PROD_BASE_TAGS:
        assert t not in tags
    assert "symbol-SYNTH_FBM" in tags
    assert "date-2020-01-01" in tags
    assert client.writes[0]["indexable"] is False


def test_prod_write_blocked_in_ci():
    client = _CollectingClient()
    sink = MemsysSink(mode="prod", client=client, in_ci=True)
    with pytest.raises(ProductionWriteBlocked):
        sink.write_regime_record(_rec())
    assert client.writes == []


def test_prod_write_blocked_without_explicit_allow():
    client = _CollectingClient()
    sink = MemsysSink(mode="prod", client=client, in_ci=False, allow_prod=False)
    with pytest.raises(ProductionWriteBlocked):
        sink.write_regime_record(_rec())
    assert client.writes == []


def test_prod_write_allowed_only_when_local_and_enabled():
    client = _CollectingClient()
    sink = MemsysSink(mode="prod", client=client, in_ci=False, allow_prod=True)
    sink.write_regime_record(_rec())
    assert len(client.writes) == 1
    assert all(t in client.writes[0]["tags"] for t in PROD_BASE_TAGS)


def test_scratch_mirror_when_no_client(tmp_path):
    mirror = tmp_path / "mirror.jsonl"
    sink = MemsysSink(mode="scratch", client=None, mirror_path=str(mirror))
    sink.write_regime_record(_rec())
    assert mirror.exists()
    row = json.loads(mirror.read_text().strip())
    assert "scratch-build-test" in row["tags"]


def test_memsys_sink_rejects_bad_mode():
    with pytest.raises(ValueError):
        MemsysSink(mode="bogus")
