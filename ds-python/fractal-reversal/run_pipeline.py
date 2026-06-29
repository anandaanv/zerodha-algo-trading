"""End-to-end pipeline runner (scaffold).

PR0 ships NO measures, so this wires the boundary only: DataSource -> [measure
stage: EMPTY in PR0] -> RecordSink. It proves the plumbing is connected and the
adapters resolve from env, without fabricating any regime record (there is no
measure to compute H yet -- measures arrive in PR1+).

Usage:
    FRM_DATASOURCE=fake FRM_SINK=fake python run_pipeline.py --symbols SYNTH_FBM

Local handoff (see README "LOCAL RUN"): FRM_DATASOURCE=kite + creds,
FRM_SINK=memsys_prod + FRM_ALLOW_PROD_WRITES=1, after reconciling the schema.
"""
from __future__ import annotations

import argparse

from config import get_datasource, get_sink


def main() -> None:
    parser = argparse.ArgumentParser(description="fractal-reversal pipeline")
    parser.add_argument(
        "--symbols", nargs="+", default=["SYNTH_FBM"],
        help="symbols to process (must have a fixture under FakeDataSource)",
    )
    parser.add_argument("--start", default=None)
    parser.add_argument("--end", default=None)
    args = parser.parse_args()

    datasource = get_datasource()
    sink = get_sink()
    print(f"datasource={type(datasource).__name__} sink={type(sink).__name__}")

    for symbol in args.symbols:
        df = datasource.get_daily_ohlc(symbol, args.start, args.end)
        print(f"{symbol}: fetched {len(df)} bars "
              f"[{df['date'].min().date()} .. {df['date'].max().date()}]")
        # --- MEASURE STAGE (empty in PR0) -------------------------------------
        # PR1: phase-0 harness. PR2: hurst -> records via sink.write_regime_record
        # No measure wired yet, so nothing is written. This is intentional.
    sink.close()
    print("done (no measures wired yet -- PR0 scaffold).")


if __name__ == "__main__":
    main()
