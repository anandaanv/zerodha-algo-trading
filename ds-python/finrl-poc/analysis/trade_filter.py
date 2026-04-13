"""
Trade filter: train XGBoost on combo backtest CSV features to predict WIN/LOSS.
Outputs a filtered CSV with only high-confidence trades, then runs benchmark sim.

Usage:
    python analysis/trade_filter.py /tmp/all_fno_combo_backtest.csv
    python analysis/trade_filter.py /tmp/all_fno_combo_backtest.csv --threshold 0.60
"""
import csv
import sys
import os
import argparse
import subprocess
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))


# ─── Feature extraction ──────────────────────────────────────────────────────

PATTERN_TYPES = [
    "DOUBLE_BOTTOM", "DOUBLE_TOP",
    "TRIANGLE_ASCENDING", "TRIANGLE_DESCENDING", "TRIANGLE_SYMMETRIC",
    "HNS_BULL", "HNS_BEAR", "FLAG_BULL", "FLAG_BEAR",
]

def _safe(v, default=0.0):
    try:
        return float(v) if v not in (None, "", "null") else default
    except (ValueError, TypeError):
        return default

def extract_features(row: dict) -> np.ndarray:
    """Return feature vector for one trade row."""
    pat = row.get("watching_pattern", "")
    pat_onehot = [1.0 if pat == p else 0.0 for p in PATTERN_TYPES]

    feats = pat_onehot + [
        _safe(row["rr_watching"]),
        _safe(row["rsi_at_p1"]),
        _safe(row["rsi_at_p2"]),
        _safe(row["rsi_at_p2"]) - _safe(row["rsi_at_p1"]),   # RSI divergence
        _safe(row["macd_hist_at_p1"]),
        _safe(row["macd_hist_at_p2"]),
        _safe(row["stoch_rsi_k_15m"]),
        _safe(row["daily_rsi"]),
        _safe(row["watching_pattern_height"]),
        _safe(row["e_symmetry"]),
        _safe(row["bb_peak_bbw"]),
        _safe(row.get("daily_adx", "0")),
        _safe(row.get("daily_adx_ema", "0")),
        _safe(row.get("daily_adx", "0")) - _safe(row.get("daily_adx_ema", "0")),  # ADX momentum
        _safe(row.get("adx_watching", "0")),
        _safe(row.get("adx_watching_ema", "0")),
        _safe(row.get("adx_watching", "0")) - _safe(row.get("adx_watching_ema", "0")),   # watching ADX momentum
        _safe(row.get("adx_confirm", "0")),
        _safe(row.get("adx_confirm_ema", "0")),
        _safe(row.get("adx_confirm", "0")) - _safe(row.get("adx_confirm_ema", "0")),      # confirm ADX momentum
        # MACD line + signal on watching (1h) and daily TFs
        _safe(row.get("macd_watching", "0")),
        _safe(row.get("macd_signal_watching", "0")),
        _safe(row.get("macd_watching", "0")) - _safe(row.get("macd_signal_watching", "0")),
        _safe(row.get("macd_daily", "0")),
        _safe(row.get("macd_signal_daily", "0")),
        _safe(row.get("macd_daily", "0")) - _safe(row.get("macd_signal_daily", "0")),
        # Bollinger Bands on watching (1h) and daily TFs (raw)
        _safe(row.get("bb_width_watching", "0")),
        _safe(row.get("bb_pct_b_watching", "0.5")),
        _safe(row.get("bb_width_daily", "0")),
        _safe(row.get("bb_pct_b_daily", "0.5")),
        # Derived slope / expansion features
        _safe(row.get("bb_expanding", "0")),
        _safe(row.get("bb_aligned", "0")),
        _safe(row.get("rsi_slope", "0")),
        _safe(row.get("macd_hist_slope", "0")),
        _safe(row.get("adx_slope", "0")),
    ]
    return np.array(feats, dtype=np.float32)

FEATURE_NAMES = PATTERN_TYPES + [
    "rr_watching", "rsi_p1", "rsi_p2", "rsi_div",
    "macd_p1", "macd_p2", "stoch_rsi_15m", "daily_rsi",
    "pattern_height", "symmetry", "bb_width",
    "daily_adx", "daily_adx_ema", "adx_momentum",
    "adx_watching", "adx_watching_ema", "adx_watching_mom",
    "adx_confirm", "adx_confirm_ema", "adx_confirm_mom",
    "macd_watching", "macd_sig_watching", "macd_hist_watching",
    "macd_daily", "macd_sig_daily", "macd_hist_daily",
    "bb_width_watching", "bb_pct_b_watching",
    "bb_width_daily", "bb_pct_b_daily",
    "bb_expanding", "bb_aligned",
    "rsi_slope", "macd_hist_slope", "adx_slope",
]


# ─── Train / eval ─────────────────────────────────────────────────────────────

def load_rows(csv_path: str):
    with open(csv_path) as f:
        return list(csv.DictReader(f))


def train_and_evaluate(rows: list, threshold: float, out_csv: str):
    from xgboost import XGBClassifier
    from sklearn.model_selection import TimeSeriesSplit
    from sklearn.metrics import classification_report

    # Sort chronologically
    rows = sorted(rows, key=lambda r: r["datetime"])
    X = np.array([extract_features(r) for r in rows])
    y = np.array([1 if r["result"] == "WIN" else 0 for r in rows])

    # Time-series split: train on first 70%, test on last 30%
    split = int(len(rows) * 0.70)
    X_train, X_test = X[:split], X[split:]
    y_train, y_test = y[:split], y[split:]

    model = XGBClassifier(
        n_estimators=300,
        max_depth=4,
        learning_rate=0.05,
        subsample=0.8,
        colsample_bytree=0.8,
        use_label_encoder=False,
        eval_metric="logloss",
        verbosity=0,
    )
    model.fit(X_train, y_train,
              eval_set=[(X_test, y_test)],
              verbose=False)

    proba_all = model.predict_proba(X)[:, 1]

    # ── Classification report on held-out 30%
    proba_test = proba_all[split:]
    pred_test  = (proba_test >= threshold).astype(int)
    print("\n=== Classifier performance on held-out 30% ===")
    print(classification_report(y_test, pred_test, target_names=["LOSS", "WIN"]))

    # ── Feature importance
    importances = model.feature_importances_
    ranked = sorted(zip(FEATURE_NAMES, importances), key=lambda x: -x[1])
    print("  Top-10 features:")
    for name, imp in ranked[:10]:
        bar = "█" * int(imp * 200)
        print(f"    {name:<22} {imp:.4f}  {bar}")

    # ── Filter: keep trades where P(WIN) >= threshold across full dataset
    mask = proba_all >= threshold
    filtered = [r for r, keep in zip(rows, mask) if keep]

    print(f"\n  Threshold : {threshold:.0%}")
    print(f"  All trades: {len(rows):>6,}  win={sum(y)/len(y)*100:.1f}%")
    print(f"  Filtered  : {len(filtered):>6,}  win={sum(y[mask])/max(sum(mask),1)*100:.1f}%  "
          f"kept={sum(mask)/len(rows)*100:.1f}%")

    # ── Write filtered CSV
    if filtered:
        with open(out_csv, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=filtered[0].keys())
            writer.writeheader()
            writer.writerows(filtered)
        print(f"  Filtered CSV → {out_csv}")

    return filtered, model


def run_sim(csv_path: str, label: str):
    """Run benchmark_simulation.py and capture output."""
    sim_script = os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(__file__)))),
        "scripts", "benchmark_simulation.py"
    )
    result = subprocess.run(
        [sys.executable, sim_script, csv_path],
        capture_output=True, text=True
    )
    lines = result.stdout.strip().split("\n")
    # Extract key lines
    print(f"\n{'='*60}")
    print(f"  {label}")
    print(f"{'='*60}")
    for line in lines:
        if any(k in line for k in ["return", "drawdown", "Trades total", "Trades executed",
                                    "Win rate", "Avg pnl", "CAGR", "Pattern"]):
            print(line)


# ─── Main ─────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("csv_path", help="Combo backtest CSV")
    parser.add_argument("--threshold", type=float, default=0.58,
                        help="Min P(WIN) to include trade (default 0.58)")
    args = parser.parse_args()

    rows = load_rows(args.csv_path)
    base   = os.path.splitext(args.csv_path)[0]
    out_csv = f"{base}_filtered_{int(args.threshold*100)}.csv"

    filtered, model = train_and_evaluate(rows, args.threshold, out_csv)

    # Run benchmark on both and compare
    run_sim(args.csv_path, "BASELINE — all trades (40% alloc)")
    if filtered:
        run_sim(out_csv, f"FILTERED — P(WIN)>={args.threshold:.0%} (40% alloc)")
