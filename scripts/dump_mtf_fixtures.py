#!/usr/bin/env python3
"""
Dump weekly/daily/hourly OHLC fixtures from local MySQL `candle` table for the 15-stock
multi-TF narrative run.

Per owner instruction (2026-05-22): "local mysql. we want stale data." — pulls whatever is
in the DB right now, no Kite calls. Writes per-stock-per-TF fixture JSONs to
`src/test/resources/fixtures/mtf/{symbol}_{tf}.json`.

Coverage caps (matches the Kite-limit framing in the owner spec):
- Week: all available bars (~11yr at ~595 bars for these stocks)
- Day:  last 2000 bars (~8 years on Day TF)
- OneHour: last 2000 bars (~16 months on Hour TF)

Schema notes (verified from `DESC candle` / `DESC instrument`):
- candle.timeframe is an ENUM with values like 'Week','Day','OneHour'
- candle is keyed by (id, instrument_instrument_token); join on instrument.instrument_token
- timestamps are DATETIME in IST-ish naive datetime

Output JSON shape (matches the format the existing MultiStockNarrativeTest readers expect):
{
  "symbol": "RELIANCE",
  "timeframe": "Week",
  "instrument_token": 738561,
  "bar_count": 595,
  "date_range": ["2015-01-01", "2026-05-20"],
  "bars": [{"epoch_seconds": ..., "iso_ist": "...", "open": ..., "high": ..., "low": ..., "close": ..., "volume": ...}, ...]
}

Usage:
  python3 scripts/dump_mtf_fixtures.py
"""

import json
import os
import sys
from datetime import datetime, timezone, timedelta

try:
    import pymysql
except ImportError:
    print("pymysql not installed. Run: pip install pymysql", file=sys.stderr)
    sys.exit(1)


DB_CONFIG = {
    "host": "localhost",
    "user": "anand",
    "password": "password",
    "database": "algotrading",
    "charset": "utf8mb4",
}

SYMBOLS = [
    "RELIANCE", "HDFCBANK", "TCS", "INFY", "TATASTEEL",
    "SBIN", "ITC", "ADANIENT", "HINDUNILVR", "BAJFINANCE",
    "ICICIBANK", "LT", "BHARTIARTL", "MARUTI", "SUNPHARMA",
]

# (timeframe enum value, output suffix, bar cap or None for all)
# Owner instruction (2026-05-22): "500 bars is good enough for each tf" —
# uniform cap across weekly/daily/hourly, keep memories light.
TIMEFRAMES = [
    ("Week",     "weekly",  500),
    ("Day",      "daily",   500),
    ("OneHour",  "hourly",  500),
]

OUT_DIR = os.path.join(
    os.path.dirname(__file__), "..", "src", "test", "resources", "fixtures", "mtf"
)

# IST is UTC+5:30; the candle.timestamp column appears stored as naive datetimes that
# represent IST market clock (e.g. weekly bars at 09:15:00). We treat them as IST and
# write both epoch_seconds (UTC) and iso_ist for downstream readers.
IST_OFFSET = timedelta(hours=5, minutes=30)


def lookup_tokens(conn, symbols):
    """Map symbol -> instrument_token (NSE-EQ)."""
    with conn.cursor() as cur:
        placeholders = ",".join(["%s"] * len(symbols))
        cur.execute(
            f"SELECT tradingsymbol, instrument_token FROM instrument "
            f"WHERE tradingsymbol IN ({placeholders}) AND exchange='NSE' "
            f"AND (instrument_type='EQ' OR instrument_type IS NULL)",
            symbols,
        )
        rows = cur.fetchall()
    out = {sym: tok for sym, tok in rows}
    missing = [s for s in symbols if s not in out]
    if missing:
        raise RuntimeError(f"Missing tokens for: {missing}")
    return out


def dump_one(conn, symbol, token, tf_enum, tf_label, cap):
    """Read candle rows for this (token, tf) and return (bar_count, [rows], (first_iso, last_iso))."""
    with conn.cursor() as cur:
        # Order ASC so the output is chronological. If `cap` is set, we want the LAST `cap`
        # bars — fetch the most recent via DESC then reverse.
        if cap:
            cur.execute(
                "SELECT timestamp, open, high, low, close, volume "
                "FROM candle WHERE instrument_instrument_token=%s AND timeframe=%s "
                "ORDER BY timestamp DESC LIMIT %s",
                (token, tf_enum, cap),
            )
            rows = list(reversed(cur.fetchall()))
        else:
            cur.execute(
                "SELECT timestamp, open, high, low, close, volume "
                "FROM candle WHERE instrument_instrument_token=%s AND timeframe=%s "
                "ORDER BY timestamp ASC",
                (token, tf_enum),
            )
            rows = cur.fetchall()
    bars = []
    for ts, o, h, l, c, v in rows:
        # Treat ts as IST wall clock; convert to epoch_seconds (UTC).
        ist_dt = ts  # naive
        utc_dt = ist_dt - IST_OFFSET
        utc_dt = utc_dt.replace(tzinfo=timezone.utc)
        epoch = int(utc_dt.timestamp())
        iso_ist = ist_dt.strftime("%Y-%m-%dT%H:%M:%S+05:30")
        bars.append({
            "epoch_seconds": epoch,
            "iso_ist": iso_ist,
            "open": float(o) if o is not None else 0.0,
            "high": float(h) if h is not None else 0.0,
            "low":  float(l) if l is not None else 0.0,
            "close": float(c) if c is not None else 0.0,
            "volume": float(v) if v is not None else 0.0,
        })
    if not bars:
        return 0, [], (None, None)
    return len(bars), bars, (bars[0]["iso_ist"][:10], bars[-1]["iso_ist"][:10])


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    conn = pymysql.connect(**DB_CONFIG)
    try:
        tokens = lookup_tokens(conn, SYMBOLS)
        print(f"Tokens resolved for {len(tokens)} symbols")
        summary = []  # list of (sym, tf, count, first, last)
        for symbol in SYMBOLS:
            token = tokens[symbol]
            for tf_enum, tf_label, cap in TIMEFRAMES:
                count, bars, (first, last) = dump_one(conn, symbol, token, tf_enum, tf_label, cap)
                if count == 0:
                    print(f"  [{symbol} {tf_label}] no data — skipping")
                    summary.append((symbol, tf_label, 0, None, None, None))
                    continue
                out_path = os.path.join(OUT_DIR, f"{symbol.lower()}_{tf_label}.json")
                payload = {
                    "symbol": symbol,
                    "timeframe": tf_enum,
                    "instrument_token": token,
                    "bar_count": count,
                    "date_range": [first, last],
                    "bars": bars,
                }
                with open(out_path, "w") as f:
                    json.dump(payload, f)
                print(f"  [{symbol} {tf_label}] {count} bars  {first} → {last}  → {out_path}")
                summary.append((symbol, tf_label, count, first, last, out_path))
        # Print summary table
        print("\n=== Summary ===")
        print(f"{'symbol':<12} {'tf':<8} {'bars':>6}  {'first':<12} {'last':<12}")
        for sym, tf, cnt, f1, l1, _ in summary:
            print(f"{sym:<12} {tf:<8} {cnt:>6}  {f1 or '-':<12} {l1 or '-':<12}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
