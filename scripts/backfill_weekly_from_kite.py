#!/usr/bin/env python3
"""
Backfill weekly candles (timeframe='Week') for NFO universe from Kite REST API.
Wipes existing Week rows per instrument and re-fetches clean bars from Kite.

Usage:
  python3 backfill_weekly_from_kite.py                 # dry-run: list instruments + counts
  python3 backfill_weekly_from_kite.py --apply         # actually call Kite + delete + insert
  python3 backfill_weekly_from_kite.py --symbol ALKEM  # one symbol only
  python3 backfill_weekly_from_kite.py --limit 5       # process first N instruments
"""

import sys
import time
import argparse
from datetime import datetime, timezone
import pymysql
import requests

# Configuration
KITE_BASE_URL = "https://api.kite.trade/instruments/historical"
KITE_HEADERS = {
    "X-Kite-Version": "3",
}

# Date ranges for backfill (split into 3 chunks, ~1800 days each to stay under Kite's 2000-day limit)
RANGES = [
    ("2015-01-01", "2019-12-31"),   # ~5 years
    ("2020-01-01", "2024-12-31"),   # ~5 years
    ("2025-01-01", None),           # 2025 to today (computed at runtime)
]

# MySQL config
DB_CONFIG = {
    "host": "localhost",
    "user": "anand",
    "password": "password",
    "database": "algotrading",
    "charset": "utf8mb4",
}

# Rate limiting: 3 req/sec = 0.34s per request
RATE_LIMIT_SEC = 0.34


def get_kite_creds():
    """Fetch fresh Kite API key and access token from DB."""
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT api_key, access_token FROM user_kite_config WHERE id=1"
            )
            row = cursor.fetchone()
            if not row:
                raise ValueError("No kite config found in DB (id=1)")
            return row[0], row[1]
    finally:
        conn.close()


def get_fno_instruments():
    """Fetch FNO universe (NSE/EQ spot symbols) with existing Week candles."""
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT i.instrument_token, i.tradingsymbol
                FROM index_symbols ix
                JOIN instrument i ON i.tradingsymbol = ix.exchange_symbol AND i.exchange = 'NSE'
                WHERE ix.index_name = 'FNO' AND i.segment = 'NSE'
                ORDER BY i.tradingsymbol
                """
            )
            return cursor.fetchall()
    finally:
        conn.close()


def fetch_kite_candles(api_key, access_token, instrument_token, from_date, to_date):
    """
    Fetch weekly candles from Kite for a single date range.
    Returns list of [timestamp, open, high, low, close, volume] or None on error.
    """
    url = f"{KITE_BASE_URL}/{instrument_token}/week"
    headers = KITE_HEADERS.copy()
    headers["Authorization"] = f"token {api_key}:{access_token}"
    params = {"from": from_date, "to": to_date}

    try:
        resp = requests.get(url, headers=headers, params=params, timeout=15)
        if resp.status_code != 200:
            print(f"    HTTP {resp.status_code}: {resp.text[:100]}")
            return None

        data = resp.json()
        if data.get("status") != "success":
            print(f"    API error: {data.get('message', 'unknown')}")
            return None

        candles = data.get("data", {}).get("candles", [])
        return candles
    except requests.RequestException as e:
        print(f"    Request error: {e}")
        return None


def parse_candle_timestamp(ts_str):
    """Parse ISO 8601 timestamp to datetime and epoch seconds."""
    # ts_str looks like "2023-01-02T10:00:00+0530"
    # Python's fromisoformat doesn't handle +0530, so handle manually
    if ts_str.endswith("+0530"):
        ts_str = ts_str[:-5]  # strip "+0530"
        dt = datetime.fromisoformat(ts_str)
    else:
        dt = datetime.fromisoformat(ts_str)

    # Convert to epoch seconds
    epoch = int(dt.timestamp())
    # MySQL timestamp(6)
    mysql_ts = dt.strftime("%Y-%m-%d %H:%M:%S.%f")
    return epoch, mysql_ts, dt


def reserve_sequence_range(connection, n_ids: int) -> int:
    """
    Atomically reserve `n_ids` consecutive IDs from candle_seq. Returns the first ID.
    """
    try:
        with connection.cursor() as cur:
            # Use FOR UPDATE for row lock to make this atomic.
            cur.execute("SELECT next_val FROM candle_seq FOR UPDATE")
            row = cur.fetchone()
            if row is None or row[0] is None:
                raise RuntimeError("candle_seq table is empty or has null next_val")
            start_id = int(row[0])
            cur.execute("UPDATE candle_seq SET next_val = %s", (start_id + n_ids,))
        connection.commit()
        return start_id
    except pymysql.Error as e:
        connection.rollback()
        raise RuntimeError(f"Failed to reserve sequence range: {e}")


def backfill_instrument(
    conn, api_key, access_token, instrument_token, tradingsymbol, next_id_ref
):
    """
    Fetch weekly candles for an instrument from all date ranges,
    dedupe, and insert into DB in a single transaction.
    next_id_ref is a dict with key 'id' that tracks the next available ID to use.
    Returns (success: bool, inserted: int, wiped: int, date_range: str, error: str or None)
    """
    # Resolve date ranges (handle None end_date = today)
    ranges = []
    for start, end in RANGES:
        if end is None:
            end = datetime.now().strftime("%Y-%m-%d")
        ranges.append((start, end))

    # Fetch all ranges
    all_candles = []
    for idx, (start, end) in enumerate(ranges, 1):
        candles = fetch_kite_candles(
            api_key, access_token, instrument_token, start, end
        )
        if candles is None:
            return (False, 0, 0, f"{ranges[0][0]}..{ranges[-1][1]}", f"Range {idx} fetch failed")
        all_candles.extend(candles)
        time.sleep(RATE_LIMIT_SEC)

    # Combine and dedupe by timestamp
    if not all_candles:
        return (False, 0, 0, f"{ranges[0][0]}..{ranges[-1][1]}", "No candles returned")

    # Dedupe by first element (timestamp string)
    seen_ts = {}
    for candle in all_candles:
        ts_str = candle[0]
        if ts_str not in seen_ts:
            seen_ts[ts_str] = candle

    unique_candles = list(seen_ts.values())
    unique_candles.sort(key=lambda c: c[0])  # Sort by timestamp

    # Parse and prepare insert rows
    rows = []
    for i, candle in enumerate(unique_candles):
        ts_str, o, h, l, c, v = candle
        epoch, mysql_ts, dt = parse_candle_timestamp(ts_str)

        # Use next_id_ref to allocate sequential IDs from candle_seq
        candle_id = next_id_ref['id']
        next_id_ref['id'] += 1

        rows.append(
            (
                candle_id,
                o,
                h,
                l,
                c,
                v,
                "Week",
                mysql_ts,
                instrument_token,
            )
        )

    # Single transaction: insert (no delete, since we wipe all Week rows upfront)
    try:
        with conn.cursor() as cursor:
            # Bulk insert
            if rows:
                cursor.executemany(
                    """
                    INSERT INTO candle (id, open, high, low, close, volume, timeframe, timestamp, instrument_instrument_token)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """,
                    rows,
                )
                inserted = cursor.rowcount
            else:
                inserted = 0

        conn.commit()
        date_range = f"{unique_candles[0][0][:10]}..{unique_candles[-1][0][:10]}"
        return (True, inserted, 0, date_range, None)

    except pymysql.Error as e:
        conn.rollback()
        return (False, 0, 0, "", f"DB error: {e}")


def main():
    parser = argparse.ArgumentParser(
        description="Backfill weekly candles from Kite for NFO universe"
    )
    parser.add_argument(
        "--apply", action="store_true", help="Apply changes (delete + insert)"
    )
    parser.add_argument(
        "--auto-confirm", action="store_true", help="Auto-confirm BACKFILL prompt"
    )
    parser.add_argument(
        "--symbol", type=str, help="Process single symbol only (e.g. ALKEM)"
    )
    parser.add_argument("--limit", type=int, help="Process first N instruments")
    args = parser.parse_args()

    print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] Starting backfill script")

    # Get Kite credentials
    print("Fetching Kite credentials...")
    api_key, access_token = get_kite_creds()
    print(f"  api_key: {api_key[:4]}...{api_key[-4:]}")

    # Get instruments
    print("Fetching NFO instruments with existing Week candles...")
    instruments = get_fno_instruments()
    print(f"  Found {len(instruments)} instruments")

    # Filter if --symbol provided
    if args.symbol:
        instruments = [(tok, sym) for tok, sym in instruments if sym == args.symbol]
        if not instruments:
            print(f"ERROR: Symbol {args.symbol} not found in DB with Week candles")
            sys.exit(1)

    # Apply --limit
    if args.limit:
        instruments = instruments[: args.limit]

    # Dry-run: count existing candles
    print("\nDRY RUN: Counting existing Week candles per instrument...")
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            for tok, sym in instruments[:5]:  # Show first 5
                cursor.execute(
                    "SELECT COUNT(*) FROM candle WHERE instrument_instrument_token = %s AND timeframe = 'Week'",
                    (tok,),
                )
                count = cursor.fetchone()[0]
                print(f"  {sym:15s} tok={tok:10d}: {count:5d} weeks")

            if len(instruments) > 5:
                print(f"  ... and {len(instruments) - 5} more")
    finally:
        conn.close()

    if not args.apply:
        print("\nDRY RUN complete. Run with --apply to backfill from Kite.")
        return

    # Confirm before applying
    end_date = datetime.now().strftime("%Y-%m-%d")
    print("\n" + "=" * 70)
    print(f"APPLY MODE: Will delete all Week candles for {len(instruments)} instruments")
    print(f"and re-fetch from Kite (2015-01-01 to {end_date}).")
    print("=" * 70)

    if args.auto_confirm:
        print("Auto-confirming BACKFILL (--auto-confirm flag set)")
        confirm = "BACKFILL"
    else:
        confirm = input("Type 'BACKFILL' to confirm: ").strip()

    if confirm != "BACKFILL":
        print("Aborted.")
        sys.exit(0)

    # Wipe ALL existing Week candle rows before re-backfill
    print("\nWiping ALL existing Week candle rows before re-backfill...")
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            cursor.execute("DELETE FROM candle WHERE timeframe = 'Week'")
            wiped_total = cursor.rowcount
        conn.commit()
        print(f"  wiped {wiped_total} rows from candle.Week")
    finally:
        conn.close()

    # Reserve ID range for all candles we're about to insert
    print(f"\nReserving ID range from candle_seq...")
    conn = pymysql.connect(**DB_CONFIG)
    try:
        # First pass: count total candles across all instruments
        total_candles_estimate = 0
        for tok, sym in instruments:
            # Quick estimate: assume ~50-60 weeks per instrument (10+ years of data)
            # We'll actually count by doing a dry fetch, but for speed just estimate
            total_candles_estimate += 550  # Conservative estimate

        # Reserve with a buffer
        seq_start = reserve_sequence_range(conn, total_candles_estimate + 1000)
        print(f"  Reserved IDs starting from: {seq_start}")
        next_id_ref = {'id': seq_start}
    finally:
        conn.close()

    # Apply backfill
    print()
    successful = 0
    failed = []
    total_inserted = 0

    conn = pymysql.connect(**DB_CONFIG)
    try:
        for idx, (tok, sym) in enumerate(instruments, 1):
            success, inserted, _, date_range, error = backfill_instrument(
                conn, api_key, access_token, tok, sym, next_id_ref
            )

            if success:
                print(
                    f"[{idx:3d}/{len(instruments)}] {sym:15s} tok={tok:10d}: inserted={inserted:5d} range={date_range}"
                )
                successful += 1
                total_inserted += inserted
            else:
                print(
                    f"[{idx:3d}/{len(instruments)}] {sym:15s} tok={tok:10d}: FAILED - {error}"
                )
                failed.append((sym, error))

            time.sleep(RATE_LIMIT_SEC)

    finally:
        conn.close()

    # Summary
    print("\n" + "=" * 70)
    print(f"Total instruments processed: {len(instruments)}")
    print(f"Successful: {successful}")
    if failed:
        print(f"Failed: {len(failed)}")
        for sym, err in failed:
            print(f"  {sym}: {err}")
    print(f"Total wiped (global): {wiped_total}")
    print(f"Total inserted: {total_inserted}")

    # Final count
    print("\nVerifying final Week candle count...")
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT COUNT(*) FROM candle WHERE timeframe='Week'"
            )
            final_count = cursor.fetchone()[0]
            print(f"Final Week candle count in DB: {final_count}")
    finally:
        conn.close()

    print("=" * 70)


if __name__ == "__main__":
    main()
