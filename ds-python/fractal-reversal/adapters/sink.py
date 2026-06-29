"""RecordSink adapter boundary.

A RecordSink persists one regime-record (a dict, see records/schema.py) per
symbol/day. Three behaviours:

  - FakeSink   : appends JSONL to records/out/*.jsonl for inspection. Default.
  - MemsysSink : REAL memsys writer, but WRITE-GATED. At cloud-build time it may
                 ONLY write to a scratch tag; it must NEVER touch production
                 regime-record tags. Records computed from FIXTURE data are
                 garbage that look real -- they must not pollute team algotrade.

Why the gate matters: the production AI-Trader-v2 scan pipeline consumes records
tagged ['ai-trader-v2','regime-record',...]. A fixture-derived record under that
tag is indistinguishable from a real one downstream. So scratch mode uses a
disjoint tag set and prod mode is hard-blocked unless explicitly enabled in a
LOCAL run against real Kite data.
"""
from __future__ import annotations

import json
import os
from abc import ABC, abstractmethod
from typing import Optional

# Production tags the live pipeline reads. Scratch writes must never collide.
PROD_BASE_TAGS = ["ai-trader-v2", "regime-record"]
SCRATCH_BASE_TAGS = ["fractal-reversal-module", "scratch-build-test"]


class RecordSink(ABC):
    @abstractmethod
    def write_regime_record(self, record: dict) -> None:
        raise NotImplementedError

    def close(self) -> None:  # optional resource hook
        pass


def _record_tags(record: dict, base_tags: list) -> list:
    """Per-record tags: base + symbol/date selectors (matches phase-1 spec)."""
    tags = list(base_tags)
    sym = record.get("symbol")
    date = record.get("date")
    if sym:
        tags.append(f"symbol-{sym}")
    if date:
        tags.append(f"date-{date}")
    return tags


class FakeSink(RecordSink):
    """Append records as JSON lines to records/out/<name>.jsonl."""

    def __init__(self, out_path: Optional[str] = None):
        if out_path is None:
            out_path = os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                "records",
                "out",
                "records.jsonl",
            )
        self.out_path = out_path
        os.makedirs(os.path.dirname(self.out_path), exist_ok=True)

    def write_regime_record(self, record: dict) -> None:
        with open(self.out_path, "a") as f:
            f.write(json.dumps(record, sort_keys=True) + "\n")


class ProductionWriteBlocked(RuntimeError):
    """Raised when a prod memsys write is attempted from a gated context."""


class MemsysSink(RecordSink):
    """Real memsys writer, write-gated by mode.

    mode='scratch' : write under SCRATCH_BASE_TAGS only. Safe in cloud/CI.
    mode='prod'    : write under PROD_BASE_TAGS. HARD-BLOCKED unless running
                     locally with FRM_ALLOW_PROD_WRITES=1 (and not in CI). This
                     is the only path that can pollute the live pipeline, so it
                     is off by default and refuses to run in the cloud build.

    The actual memsys call is delegated to an injected `client` exposing
        client.memory_write(content: str, tags: list, indexable: bool) -> None
    In the cloud build no Python memsys client is wired (`client=None`), so a
    scratch write records to a local JSONL mirror instead of hitting the network
    -- the gate logic (tag selection + prod block) is what PR0 proves, and it is
    fully unit-tested with an injected fake client.
    """

    def __init__(
        self,
        mode: str = "scratch",
        client: Optional[object] = None,
        allow_prod: Optional[bool] = None,
        in_ci: Optional[bool] = None,
        mirror_path: Optional[str] = None,
    ):
        if mode not in ("scratch", "prod"):
            raise ValueError(f"mode must be 'scratch' or 'prod', got {mode!r}")
        self.mode = mode
        self.client = client
        if allow_prod is None:
            allow_prod = os.environ.get("FRM_ALLOW_PROD_WRITES") == "1"
        self.allow_prod = allow_prod
        if in_ci is None:
            in_ci = os.environ.get("CI") is not None
        self.in_ci = in_ci
        if mirror_path is None:
            mirror_path = os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                "records",
                "out",
                "memsys_scratch_mirror.jsonl",
            )
        self.mirror_path = mirror_path

    def _resolve_tags(self, record: dict) -> list:
        if self.mode == "scratch":
            return _record_tags(record, SCRATCH_BASE_TAGS)
        # prod path -- gated below before we ever get here
        return _record_tags(record, PROD_BASE_TAGS)

    def write_regime_record(self, record: dict) -> None:
        if self.mode == "prod":
            if self.in_ci:
                raise ProductionWriteBlocked(
                    "Refusing prod memsys write inside CI/cloud build. "
                    "Prod regime-record writes happen only in a LOCAL run "
                    "against real Kite data."
                )
            if not self.allow_prod:
                raise ProductionWriteBlocked(
                    "Prod memsys write blocked. Set FRM_ALLOW_PROD_WRITES=1 "
                    "in a local run against REAL data to enable it."
                )

        tags = self._resolve_tags(record)
        # Belt-and-braces: a scratch write must never carry a prod tag.
        if self.mode == "scratch" and any(t in tags for t in PROD_BASE_TAGS):
            raise ProductionWriteBlocked(
                "Scratch write resolved to a production tag -- aborting."
            )

        content = json.dumps(record, sort_keys=True)
        if self.client is not None:
            self.client.memory_write(content=content, tags=tags, indexable=False)
        else:
            # No live client in the cloud build: mirror scratch writes locally.
            os.makedirs(os.path.dirname(self.mirror_path), exist_ok=True)
            with open(self.mirror_path, "a") as f:
                f.write(json.dumps({"tags": tags, "record": record},
                                   sort_keys=True) + "\n")
