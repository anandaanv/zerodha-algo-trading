"""Environment wiring for the fractal-reversal module.

Env vars (defaults are safe for cloud/CI -- creds-free, no prod writes):

  FRM_DATASOURCE = fake | kite                       (default: fake)
  FRM_SINK       = fake | memsys_scratch | memsys_prod (default: fake)

`memsys_prod` is for LOCAL runs against real Kite data only; it is additionally
gated inside MemsysSink (see adapters/sink.py) and refuses to run in CI.
"""
from __future__ import annotations

import os

from adapters.datasource import DataSource, FakeDataSource, KiteDataSource
from adapters.sink import FakeSink, MemsysSink, RecordSink

DEFAULT_DATASOURCE = "fake"
DEFAULT_SINK = "fake"


def get_datasource(name: str | None = None) -> DataSource:
    name = (name or os.environ.get("FRM_DATASOURCE", DEFAULT_DATASOURCE)).lower()
    if name == "fake":
        return FakeDataSource()
    if name == "kite":
        return KiteDataSource()
    raise ValueError(
        f"FRM_DATASOURCE must be 'fake' or 'kite', got {name!r}"
    )


def get_sink(name: str | None = None) -> RecordSink:
    name = (name or os.environ.get("FRM_SINK", DEFAULT_SINK)).lower()
    if name == "fake":
        return FakeSink()
    if name == "memsys_scratch":
        return MemsysSink(mode="scratch")
    if name == "memsys_prod":
        return MemsysSink(mode="prod")
    raise ValueError(
        f"FRM_SINK must be 'fake', 'memsys_scratch' or 'memsys_prod', "
        f"got {name!r}"
    )
