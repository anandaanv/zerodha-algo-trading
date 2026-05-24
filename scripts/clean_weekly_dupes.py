#!/usr/bin/env python3
"""
clean_weekly_dupes.py — dedupe intra-week snapshots in candle table.

Usage:
  python clean_weekly_dupes.py                 # dry-run, prints per-symbol counts + aggregate
  python clean_weekly_dupes.py --apply         # actually DELETE rows. Prompts for y/N first.
  python clean_weekly_dupes.py --symbol ALKEM  # restrict to one tradingsymbol (works in both modes)

Reads MySQL creds from .local-dev-credentials at the repo root if present, else uses
environment vars DB_USER, DB_PASS, DB_HOST, DB_NAME (defaults: anand / password / localhost / algotrading).
"""

import sys
import os
import re
from datetime import datetime
from pathlib import Path
from collections import defaultdict
import argparse

# Try pymysql first, fall back to mysql.connector
try:
    import pymysql
    USE_PYMYSQL = True
except ImportError:
    try:
        import mysql.connector
        USE_PYMYSQL = False
    except ImportError:
        print("ERROR: Neither pymysql nor mysql.connector installed.")
        print("Install one: pip install pymysql")
        sys.exit(1)


def read_local_dev_credentials():
    """
    Read .local-dev-credentials file if it exists.
    Returns dict of KEY=value pairs (for reference), but we use hardcoded MySQL defaults.
    """
    cred_file = Path("/codes/algotrade/zerodha-algo-trading/.local-dev-credentials")
    if not cred_file.exists():
        return {}

    creds = {}
    try:
        with open(cred_file, 'r') as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#'):
                    if '=' in line:
                        k, v = line.split('=', 1)
                        creds[k.strip()] = v.strip()
    except Exception as e:
        print(f"Warning: could not read .local-dev-credentials: {e}")

    return creds


def get_db_connection():
    """
    Create MySQL connection. Use hardcoded defaults: user=anand, password=password,
    db=algotrading, host=localhost.
    """
    db_user = os.environ.get('DB_USER', 'anand')
    db_pass = os.environ.get('DB_PASS', 'password')
    db_host = os.environ.get('DB_HOST', 'localhost')
    db_name = os.environ.get('DB_NAME', 'algotrading')

    if USE_PYMYSQL:
        conn = pymysql.connect(
            host=db_host,
            user=db_user,
            password=db_pass,
            database=db_name,
            autocommit=False,
            charset='utf8mb4'
        )
    else:
        conn = mysql.connector.connect(
            host=db_host,
            user=db_user,
            password=db_pass,
            database=db_name,
            autocommit=False,
            charset='utf8mb4'
        )

    return conn


def get_iso_week(dt):
    """
    Get (iso_year, iso_week) tuple from a datetime object (assumed to be in IST).
    """
    iso_cal = dt.isocalendar()
    return (iso_cal[0], iso_cal[1])


def fetch_week_candles(conn, symbol_filter=None):
    """
    Fetch all rows from candle table where timeframe='Week'.
    Returns list of dicts: {id, instrument_instrument_token, timestamp, tradingsymbol}.
    """
    query = """
        SELECT c.id, c.instrument_instrument_token, c.timestamp, i.tradingsymbol
        FROM candle c
        JOIN instrument i ON c.instrument_instrument_token = i.instrument_token
        WHERE c.timeframe = 'Week'
    """

    params = []
    if symbol_filter:
        query += " AND i.tradingsymbol = %s"
        params.append(symbol_filter)

    query += " ORDER BY c.instrument_instrument_token, c.timestamp"

    cursor = conn.cursor()
    try:
        cursor.execute(query, params)
        rows = cursor.fetchall()

        result = []
        for row in rows:
            result.append({
                'id': row[0],
                'instrument_instrument_token': row[1],
                'timestamp': row[2],
                'tradingsymbol': row[3]
            })
        return result
    finally:
        cursor.close()


def group_by_iso_week(candles):
    """
    Group candles by (instrument_instrument_token, iso_year, iso_week).
    Returns dict: {(token, iso_year, iso_week): [candle_dicts, ...]}.
    """
    groups = defaultdict(list)

    for candle in candles:
        ts = candle['timestamp']
        token = candle['instrument_instrument_token']
        iso_week = get_iso_week(ts)
        iso_year, iso_week_num = iso_week

        key = (token, iso_year, iso_week_num)
        groups[key].append(candle)

    return groups


def compute_deletions(groups):
    """
    For each group, keep the row with latest timestamp; collect IDs to delete.
    Returns (delete_ids, keep_ids, per_symbol_stats).

    per_symbol_stats: {symbol: {total, distinct_weeks, to_delete, to_keep}}
    """
    delete_ids = []
    keep_ids = []
    per_symbol = defaultdict(lambda: {'total': 0, 'distinct_weeks': 0, 'to_delete': 0, 'to_keep': 0})

    for (token, iso_year, iso_week_num), candles_in_week in groups.items():
        # Each group represents one (token, iso_week)
        # Sort by timestamp descending; keep the latest
        sorted_candles = sorted(candles_in_week, key=lambda x: x['timestamp'], reverse=True)

        symbol = sorted_candles[0]['tradingsymbol']

        # Keep the first (latest timestamp)
        keep_id = sorted_candles[0]['id']
        keep_ids.append(keep_id)
        per_symbol[symbol]['to_keep'] += 1

        # Delete the rest
        for candle in sorted_candles[1:]:
            delete_ids.append(candle['id'])
            per_symbol[symbol]['to_delete'] += 1

        per_symbol[symbol]['distinct_weeks'] += 1
        per_symbol[symbol]['total'] += len(candles_in_week)

    return delete_ids, keep_ids, per_symbol


def print_dry_run_summary(per_symbol_stats, sample_deletions, candles):
    """
    Print a summary table and sample deletion candidates.
    """
    print("\n" + "=" * 100)
    print("DRY-RUN SUMMARY")
    print("=" * 100)

    print(f"\n{'Symbol':<12} {'Total Rows':<15} {'Distinct Weeks':<18} {'Rows to Delete':<18} {'Rows to Keep':<15}")
    print("-" * 100)

    total_rows = 0
    total_weeks = 0
    total_delete = 0
    total_keep = 0

    for symbol in sorted(per_symbol_stats.keys()):
        stats = per_symbol_stats[symbol]
        total_rows += stats['total']
        total_weeks += stats['distinct_weeks']
        total_delete += stats['to_delete']
        total_keep += stats['to_keep']

        print(f"{symbol:<12} {stats['total']:<15} {stats['distinct_weeks']:<18} {stats['to_delete']:<18} {stats['to_keep']:<15}")

    print("-" * 100)
    print(f"{'TOTAL':<12} {total_rows:<15} {total_weeks:<18} {total_delete:<18} {total_keep:<15}")
    print("=" * 100)

    # Sample deletions
    if sample_deletions:
        print(f"\nSample deletion candidates (up to 5):\n")
        for i, (iso_week_info, week_candles) in enumerate(sample_deletions[:5]):
            iso_year, iso_week_num = iso_week_info
            print(f"  ISO week {iso_year}-W{iso_week_num:02d}:")
            sorted_week = sorted(week_candles, key=lambda x: x['timestamp'], reverse=True)
            for j, candle in enumerate(sorted_week):
                marker = "[KEEP]" if j == 0 else "[DELETE]"
                print(f"    {marker} id={candle['id']:<8} timestamp={candle['timestamp']} symbol={candle['tradingsymbol']}")
            print()


def delete_rows(conn, delete_ids, dry_run=False):
    """
    Delete rows in batches of 1000. If dry_run=True, just print what would be deleted.
    """
    if not delete_ids:
        print("No rows to delete.")
        return

    if dry_run:
        print(f"\n[DRY-RUN] Would delete {len(delete_ids)} rows.")
        return

    # Ask for confirmation
    print(f"\n{'=' * 100}")
    print(f"APPLY MODE: About to delete {len(delete_ids)} rows from candle table (timeframe='Week').")
    print(f"{'=' * 100}")

    response = input(f"\nType 'DELETE' to confirm deletion of {len(delete_ids)} rows: ").strip()
    if response != 'DELETE':
        print("Aborted.")
        conn.rollback()
        return

    print(f"\nDeleting {len(delete_ids)} rows in batches of 1000...")

    cursor = conn.cursor()
    try:
        batch_size = 1000
        for i in range(0, len(delete_ids), batch_size):
            batch = delete_ids[i:i+batch_size]
            placeholders = ','.join(['%s'] * len(batch))
            query = f"DELETE FROM candle WHERE id IN ({placeholders})"
            cursor.execute(query, batch)

            deleted_so_far = min(i + batch_size, len(delete_ids))
            if deleted_so_far % 5000 == 0 or deleted_so_far == len(delete_ids):
                print(f"  Deleted {deleted_so_far}/{len(delete_ids)} rows...")

        conn.commit()
        print(f"\nSuccessfully deleted {len(delete_ids)} rows.")

        # Verify final count
        verify_query = "SELECT COUNT(*) FROM candle WHERE timeframe='Week'"
        cursor.execute(verify_query)
        final_count = cursor.fetchone()[0]
        print(f"Final candle table row count for timeframe='Week': {final_count}")

    except Exception as e:
        print(f"ERROR during deletion: {e}")
        conn.rollback()
        raise
    finally:
        cursor.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Actually delete rows (prompts for confirmation).')
    parser.add_argument('--symbol', type=str, default=None, help='Restrict to a specific tradingsymbol.')

    args = parser.parse_args()

    # Read creds file (for reference; we use hardcoded MySQL defaults)
    read_local_dev_credentials()

    # Connect to DB
    print("Connecting to MySQL...")
    try:
        conn = get_db_connection()
    except Exception as e:
        print(f"ERROR: Could not connect to MySQL: {e}")
        sys.exit(1)

    print("Connected.")

    try:
        # Fetch week candles
        print(f"\nFetching all candles with timeframe='Week'{f' for symbol {args.symbol}' if args.symbol else ''}...")
        candles = fetch_week_candles(conn, symbol_filter=args.symbol)
        print(f"Fetched {len(candles)} rows.")

        if not candles:
            print("No candles found. Exiting.")
            return

        # Group by ISO week
        print("Grouping by (instrument, ISO-week)...")
        groups = group_by_iso_week(candles)
        print(f"Found {len(groups)} distinct (instrument, ISO-week) groups.")

        # Compute deletions
        print("Computing deletion candidates...")
        delete_ids, keep_ids, per_symbol_stats = compute_deletions(groups)

        # Prepare sample deletions for display
        sample_deletions = []
        for (token, iso_year, iso_week_num), candles_in_week in list(groups.items())[:5]:
            sample_deletions.append(((iso_year, iso_week_num), candles_in_week))

        # Print summary
        print_dry_run_summary(per_symbol_stats, sample_deletions, candles)

        # If --apply, delete; else just print what would happen
        if args.apply:
            delete_rows(conn, delete_ids, dry_run=False)
        else:
            print(f"\n[DRY-RUN] Not deleting. Re-run with --apply to actually delete {len(delete_ids)} rows.")

    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)
    finally:
        conn.close()


if __name__ == '__main__':
    main()
