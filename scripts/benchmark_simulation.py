"""
Standard Backtest Benchmark Simulation
=======================================
Evaluates any combo backtest CSV using realistic concurrent position sizing.

Methodology (locked in as standard evaluation criteria):
- Capital: ₹10,00,000 starting
- Allocation: 40% of AVAILABLE (free) capital per trade
  → Never skips trades (free cash never depletes to zero)
  → Auto-throttles as more positions open (Kelly-like behaviour)
- Concurrency: Fully concurrent — trades overlap based on real entry/exit times
  → Exit time = entry_time + bars_to_result × 15 minutes (15m confirmation TF)
- Brokerage: Already baked into pnl_pct in CSV (TRADE_OVERHEAD_PCT = 0.1% per trade)
  covering Zerodha flat fee + STT + transaction charges
- Compounding: Full — every rupee of profit is reinvested immediately

Usage:
    python benchmark_simulation.py /tmp/your_backtest.csv
    python benchmark_simulation.py /tmp/your_backtest.csv --alloc 0.4

Benchmark targets (from top-20 stocks, Sep 2025 – Apr 2026, 948 trades):
    40% of available → ₹10L → ₹23.2L (+131%) in 7 months, MaxDD 1.7%
"""

import csv
import sys
import argparse
from collections import defaultdict
from datetime import datetime, timedelta

MINS_PER_BAR   = 15       # confirmation TF bar size (minutes)
INITIAL_CAPITAL = 1_000_000


def load_csv(path):
    rows = []
    with open(path) as f:
        reader = csv.DictReader(f)
        for r in reader:
            rows.append(r)
    return rows


def parse_rows(rows):
    parsed = []
    for r in rows:
        r['_entry_dt'] = datetime.fromisoformat(r['datetime'].replace('Z', '+00:00'))
        r['_bars']     = int(r['bars_to_result'])
        r['_pnl']      = float(r['pnl_pct'])
        r['_exit_dt']  = (r['_entry_dt'] + timedelta(minutes=r['_bars'] * MINS_PER_BAR)
                          if r['_bars'] > 0 else None)
        parsed.append(r)
    parsed.sort(key=lambda r: r['_entry_dt'])
    end_of_data = max(r['_entry_dt'] for r in parsed) + timedelta(days=1)
    for r in parsed:
        if r['_exit_dt'] is None:
            r['_exit_dt'] = end_of_data
    return parsed


def simulate(rows, alloc_pct=0.40, initial=INITIAL_CAPITAL):
    """
    Concurrent simulation: alloc_pct of FREE cash per trade.
    Returns (final_capital, executed, skipped, monthly_pnl, max_drawdown_pct)
    """
    free        = float(initial)
    deployed    = 0.0
    open_pos    = []          # (exit_dt, alloc_inr, pnl_pct, month_str)
    skipped     = executed = 0
    monthly_pnl = defaultdict(float)
    peak        = float(initial)
    max_dd      = 0.0

    for r in rows:
        entry_dt = r['_entry_dt']

        # Close positions that have exited
        still_open = []
        for (ex_dt, alloc, pnl, month) in open_pos:
            if ex_dt <= entry_dt:
                gain      = alloc * pnl / 100.0
                free     += alloc + gain
                deployed -= alloc
                monthly_pnl[month] += gain
            else:
                still_open.append((ex_dt, alloc, pnl, month))
        open_pos = still_open

        # Portfolio drawdown (free + deployed at cost)
        portfolio = free + deployed
        peak      = max(peak, portfolio)
        max_dd    = max(max_dd, (peak - portfolio) / peak * 100)

        if free <= 0:
            skipped += 1
            continue
        allocated = free * alloc_pct
        if allocated < 100:
            skipped += 1
            continue

        free     -= allocated
        deployed += allocated
        open_pos.append((r['_exit_dt'], allocated, r['_pnl'], entry_dt.strftime('%Y-%m')))
        executed += 1

    # Close remaining positions at end
    for (ex_dt, alloc, pnl, month) in open_pos:
        gain      = alloc * pnl / 100.0
        free     += alloc + gain
        monthly_pnl[month] += gain

    return free, executed, skipped, monthly_pnl, max_dd


def print_report(rows, alloc_pct, initial=INITIAL_CAPITAL):
    parsed = parse_rows(rows)
    final, executed, skipped, mpnl, max_dd = simulate(parsed, alloc_pct, initial)

    total_ret  = (final / initial - 1) * 100
    n_months   = len(mpnl)
    cagr       = ((final / initial) ** (12 / max(n_months, 1)) - 1) * 100
    wins       = sum(1 for r in rows if r.get('result') == 'WIN')
    wr         = wins / len(rows) * 100 if rows else 0
    avg_pnl    = sum(float(r['pnl_pct']) for r in rows) / len(rows) if rows else 0

    print("=" * 60)
    print(f"  BACKTEST BENCHMARK  —  {int(alloc_pct*100)}% of available capital")
    print("=" * 60)
    print(f"  Starting capital  : ₹{initial:>12,.0f}")
    print(f"  Final capital     : ₹{final:>12,.0f}")
    print(f"  Total return      : {total_ret:>+11.1f}%")
    print(f"  CAGR (annualised) : {cagr:>+11.1f}%")
    print(f"  Max drawdown      : {max_dd:>+11.1f}%")
    print(f"  Trades total      : {len(rows):>12,}")
    print(f"  Trades executed   : {executed:>12,}")
    print(f"  Trades skipped    : {skipped:>12,}")
    print(f"  Win rate          : {wr:>11.1f}%")
    print(f"  Avg pnl/trade     : {avg_pnl:>+11.2f}%")
    print(f"  Brokerage         :    0.1% / trade (in pnl_pct)")
    print()
    print("  Monthly breakdown:")
    print(f"  {'Month':<10} {'P&L (₹)':>12} {'Month%':>8}  Capital")
    print("  " + "-" * 46)
    running = float(initial)
    for m in sorted(mpnl):
        gain = mpnl[m]
        end  = running + gain
        pct  = gain / running * 100
        print(f"  {m:<10} ₹{gain:>+11,.0f} {pct:>+7.1f}%  ₹{end/1e5:.2f}L")
        running = end
    print("=" * 60)

    # Pattern breakdown
    by_pat = defaultdict(lambda: {'n': 0, 'wins': 0, 'pnl': 0.0})
    for r in rows:
        k = r['watching_pattern']
        by_pat[k]['n']   += 1
        by_pat[k]['pnl'] += float(r['pnl_pct'])
        if r.get('result') == 'WIN':
            by_pat[k]['wins'] += 1
    print("\n  Pattern breakdown:")
    print(f"  {'Pattern':<24} {'n':>5} {'WR':>6} {'AvgPnL':>8} {'TotalPnL':>10}")
    print("  " + "-" * 56)
    for k, v in sorted(by_pat.items(), key=lambda x: -x[1]['pnl']):
        wr_p = v['wins'] / v['n'] * 100
        avg  = v['pnl'] / v['n']
        print(f"  {k:<24} {v['n']:>5} {wr_p:>5.0f}% {avg:>+7.2f}% {v['pnl']:>+9.1f}%")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Standard backtest benchmark simulation')
    parser.add_argument('csv_path', help='Path to combo backtest CSV')
    parser.add_argument('--alloc', type=float, default=0.40,
                        help='Fraction of available capital per trade (default: 0.40)')
    parser.add_argument('--capital', type=float, default=1_000_000,
                        help='Starting capital in INR (default: 1000000)')
    args = parser.parse_args()

    rows = load_csv(args.csv_path)
    print_report(rows, args.alloc, args.capital)
