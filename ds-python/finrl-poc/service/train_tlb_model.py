"""
Train trade filter model for Trendline Breakout (TLB) strategy.

Usage:
    python service/train_tlb_model.py /tmp/tlb_backtest_fno_closed.csv
    python service/train_tlb_model.py /tmp/tlb_backtest_fno_closed.csv --threshold 0.60
"""
import argparse, csv, json, os, sys
import numpy as np
import joblib

from xgboost import XGBClassifier
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report


def _safe(v, default=0.0):
    try:
        return float(v) if v not in (None, "", "null", "true", "false") else (1.0 if v == "true" else default)
    except (ValueError, TypeError):
        return default


TF_TYPES = ["15m", "1h", "1d"]

FEATURE_NAMES = [
    # Timeframe one-hot
    "tf_15m", "tf_1h", "tf_1d",
    # Direction
    "is_long",
    # Pattern metrics
    "rr", "ab_distance_atr_ratio", "atr",
    # RSI at pivots
    "rsi_at_p1", "rsi_at_p2", "rsi_divergence",
    # MACD at pivots
    "macd_hist_at_p1", "macd_hist_at_p2",
    # StochRSI
    "stoch_rsi_k",
    # Daily indicators
    "daily_rsi", "daily_adx", "daily_adx_ema", "daily_adx_momentum",
    # Watching TF indicators
    "adx_watching", "adx_watching_ema", "adx_watching_momentum",
    # MACD watching
    "macd_watching", "macd_signal_watching", "macd_hist_watching",
    # MACD daily
    "macd_daily", "macd_signal_daily", "macd_hist_daily",
    # Bollinger Bands watching
    "bb_width_watching", "bb_pct_b_watching",
    # Bollinger Bands daily
    "bb_width_daily", "bb_pct_b_daily",
    # Derived features
    "bb_expanding", "bb_aligned",
    "rsi_slope", "macd_hist_slope", "adx_slope",
]


def extract_features(row: dict) -> np.ndarray:
    tf = row.get("tf", "1h")
    tf_onehot = [1.0 if tf == t else 0.0 for t in TF_TYPES]

    direction = row.get("direction", "LONG")
    is_long = 1.0 if direction == "LONG" else 0.0

    ab_dist = _safe(row.get("ab_distance", "0"))
    atr_val = _safe(row.get("atr", "1"))
    ab_atr_ratio = ab_dist / atr_val if atr_val > 0 else 0

    feats = tf_onehot + [
        is_long,
        _safe(row.get("rr", "0")),
        ab_atr_ratio,
        atr_val,
        _safe(row.get("rsi_at_p1", "0")),
        _safe(row.get("rsi_at_p2", "0")),
        _safe(row.get("rsi_at_p2", "0")) - _safe(row.get("rsi_at_p1", "0")),
        _safe(row.get("macd_hist_at_p1", "0")),
        _safe(row.get("macd_hist_at_p2", "0")),
        _safe(row.get("stoch_rsi_k", "0")),
        _safe(row.get("daily_rsi", "0")),
        _safe(row.get("daily_adx", "0")),
        _safe(row.get("daily_adx_ema", "0")),
        _safe(row.get("daily_adx", "0")) - _safe(row.get("daily_adx_ema", "0")),
        _safe(row.get("adx_watching", "0")),
        _safe(row.get("adx_watching_ema", "0")),
        _safe(row.get("adx_watching", "0")) - _safe(row.get("adx_watching_ema", "0")),
        _safe(row.get("macd_watching", "0")),
        _safe(row.get("macd_signal_watching", "0")),
        _safe(row.get("macd_watching", "0")) - _safe(row.get("macd_signal_watching", "0")),
        _safe(row.get("macd_daily", "0")),
        _safe(row.get("macd_signal_daily", "0")),
        _safe(row.get("macd_daily", "0")) - _safe(row.get("macd_signal_daily", "0")),
        _safe(row.get("bb_width_watching", "0")),
        _safe(row.get("bb_pct_b_watching", "0.5")),
        _safe(row.get("bb_width_daily", "0")),
        _safe(row.get("bb_pct_b_daily", "0.5")),
        _safe(row.get("bb_expanding", "0")),
        _safe(row.get("bb_aligned", "0")),
        _safe(row.get("rsi_slope", "0")),
        _safe(row.get("macd_hist_slope", "0")),
        _safe(row.get("adx_slope", "0")),
    ]
    return np.array(feats, dtype=np.float32)


def load_rows(csv_path: str, mode: str = "linear"):
    with open(csv_path) as f:
        rows = list(csv.DictReader(f))
    if mode == "trail":
        # Use trailing SL columns as the result
        for r in rows:
            r["result"] = r.get("trail_result", r.get("result", ""))
            r["pnl_pct"] = r.get("trail_pnl_pct", r.get("pnl_pct", "0"))
        return [r for r in rows if r["result"] in ("WIN", "STOP_HIT")]
    return [r for r in rows if r.get("result") in ("WIN", "STOP_HIT")]


def train(csv_path: str, threshold: float, mode: str = "linear"):
    rows = load_rows(csv_path, mode)
    X = np.array([extract_features(r) for r in rows])
    y = np.array([1 if r["result"] == "WIN" else 0 for r in rows])

    print(f"Mode: {mode.upper()} | Dataset: {len(rows):,} trades | {sum(y):,} wins ({sum(y)/len(y)*100:.1f}%) | {len(y)-sum(y):,} losses")

    indices = np.arange(len(rows))
    train_idx, test_idx = train_test_split(indices, test_size=0.30, random_state=42, stratify=y)

    model = XGBClassifier(
        n_estimators=300, max_depth=4, learning_rate=0.05,
        subsample=0.8, colsample_bytree=0.8,
        eval_metric="logloss", verbosity=0,
    )
    model.fit(X[train_idx], y[train_idx], eval_set=[(X[test_idx], y[test_idx])], verbose=False)

    proba = model.predict_proba(X)[:, 1]

    # Test set evaluation
    test_proba = proba[test_idx]
    test_pred = (test_proba >= threshold).astype(int)
    print(f"\n=== Test set (30% held-out) classification @ threshold={threshold} ===")
    print(classification_report(y[test_idx], test_pred, target_names=["LOSS", "WIN"]))

    # Feature importance
    importances = model.feature_importances_
    ranked = sorted(zip(FEATURE_NAMES, importances), key=lambda x: -x[1])
    print("Top-15 features:")
    for name, imp in ranked[:15]:
        bar = "█" * int(imp * 200)
        print(f"  {name:<25} {imp:.4f}  {bar}")

    # Sweep thresholds
    print(f"\n{'Threshold':>10} {'Trades':>8} {'Wins':>6} {'WR%':>7} {'AvgPnl':>8}  (test set)")
    for thr in [0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80]:
        mask = test_proba >= thr
        if mask.sum() == 0: continue
        filtered_y = y[test_idx][mask]
        filtered_rows = [rows[i] for i, m in zip(test_idx, mask) if m]
        wr = filtered_y.mean() * 100
        avg_pnl = np.mean([_safe(r["pnl_pct"]) for r in filtered_rows])
        print(f"  {thr:>8.0%} {mask.sum():>8,} {int(filtered_y.sum()):>6} {wr:>6.1f}% {avg_pnl:>+7.2f}%")

    # Save model
    os.makedirs("service/model", exist_ok=True)
    suffix = "_trail" if mode == "trail" else ""
    model_path = f"service/model/tlb_trade_filter{suffix}.pkl"
    joblib.dump(model, model_path)
    meta = {
        "threshold": threshold,
        "features": FEATURE_NAMES,
        "trained_on": csv_path,
        "n_rows": len(rows),
        "strategy": "TRENDLINE_BREAKOUT",
        "exit_mode": mode
    }
    with open(f"service/model/tlb_meta{suffix}.json", "w") as f:
        json.dump(meta, f, indent=2)
    print(f"\nModel saved → {model_path}")

    # Write filtered test CSV
    test_mask = test_proba >= threshold
    filtered_test = [rows[i] for i, m in zip(test_idx, test_mask) if m]
    if filtered_test:
        out_csv = csv_path.replace(".csv", f"_filtered_{int(threshold*100)}.csv")
        with open(out_csv, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=filtered_test[0].keys())
            writer.writeheader()
            writer.writerows(filtered_test)
        print(f"Filtered test CSV → {out_csv}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("csv_path")
    parser.add_argument("--threshold", type=float, default=0.55)
    parser.add_argument("--mode", choices=["linear", "trail"], default="linear")
    args = parser.parse_args()
    train(args.csv_path, args.threshold, args.mode)
