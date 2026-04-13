"""
Train trade filter model on full dataset and save to disk.
Evaluation uses leave-one-month-out cross-validation (honest live-equivalent).

Usage:
    python service/train_model.py /tmp/all_fno_combo_backtest.csv
    python service/train_model.py /tmp/all_fno_combo_backtest.csv --threshold 0.70
"""
import argparse, json, os, sys
from collections import defaultdict
import numpy as np
import joblib

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from analysis.trade_filter import load_rows, extract_features, FEATURE_NAMES
from xgboost import XGBClassifier


def _safe(v, d=0.0):
    try:
        return float(v) if v not in (None, "", "null") else d
    except (ValueError, TypeError):
        return d


def evaluate_loo(rows, X, y, threshold):
    """Leave-one-month-out evaluation: train on all months except one, test on it."""
    def month_of(r): return r["datetime"][:7]

    months_order = []
    month_indices = defaultdict(list)
    for i, r in enumerate(rows):
        m = month_of(r)
        if m not in month_indices:
            months_order.append(m)
        month_indices[m].append(i)

    print(f"\n=== Leave-One-Month-Out Evaluation (threshold={threshold}) ===")
    print(f"  {'Month':<9} {'Train':>7} {'Test':>5}  {'Base WR':>7}  {'Filt N':>6}  {'Filt WR':>7}  {'Avg pnl':>8}")
    print("  " + "-" * 65)

    all_y, all_proba = [], []

    for m in months_order:
        test_idx  = np.array(month_indices[m])
        train_idx = np.array([i for i in range(len(rows)) if month_of(rows[i]) != m])

        model = XGBClassifier(
            n_estimators=300, max_depth=4, learning_rate=0.05,
            subsample=0.8, colsample_bytree=0.8,
            eval_metric="logloss", verbosity=0,
        )
        model.fit(X[train_idx], y[train_idx], verbose=False)
        proba = model.predict_proba(X[test_idx])[:, 1]

        y_test   = y[test_idx]
        mask     = proba >= threshold
        base_wr  = y_test.mean() * 100
        filt_n   = mask.sum()
        filt_wr  = y_test[mask].mean() * 100 if filt_n > 0 else 0
        pnl_filt = [_safe(rows[i]["pnl_pct"]) for i, k in zip(test_idx, mask) if k]
        avg_pnl  = np.mean(pnl_filt) if pnl_filt else 0.0

        print(f"  {m}  {len(train_idx):>7,}  {len(test_idx):>5}  "
              f"{base_wr:>7.1f}%  {filt_n:>6}  {filt_wr:>7.1f}%  {avg_pnl:>8.3f}%")

        all_y.extend(y_test.tolist())
        all_proba.extend(proba.tolist())

    all_y     = np.array(all_y)
    all_proba = np.array(all_proba)
    mask_all  = all_proba >= threshold
    pnl_total = [_safe(r["pnl_pct"]) for r, k in zip(rows, mask_all) if k]

    print("  " + "-" * 65)
    print(f"  {'TOTAL':<9}  {'':>7}  {len(all_y):>5}  "
          f"{all_y.mean()*100:>7.1f}%  {mask_all.sum():>6}  "
          f"{all_y[mask_all].mean()*100:>7.1f}%  "
          f"{np.mean(pnl_total) if pnl_total else 0:>8.3f}%")
    print(f"\n  → Expected live WR: {all_y[mask_all].mean()*100:.1f}%  "
          f"avg pnl/trade: {np.mean(pnl_total) if pnl_total else 0:.3f}%")


def train(csv_path: str, threshold: float):
    rows = load_rows(csv_path)
    rows = [r for r in rows if r.get("result") not in ("OPEN", "")]
    rows = sorted(rows, key=lambda r: r["datetime"])

    X = np.array([extract_features(r) for r in rows])
    y = np.array([1 if r["result"] == "WIN" else 0 for r in rows])

    # ── Evaluate first (LOO — honest live-equivalent benchmark)
    evaluate_loo(rows, X, y, threshold)

    # ── Train on 100% of data for deployment
    print(f"\n=== Training deployment model on full dataset ({len(rows):,} rows) ===")
    model = XGBClassifier(
        n_estimators=300, max_depth=4, learning_rate=0.05,
        subsample=0.8, colsample_bytree=0.8,
        eval_metric="logloss", verbosity=0,
    )
    model.fit(X, y, verbose=False)

    proba = model.predict_proba(X)[:, 1]
    mask  = proba >= threshold
    wr    = y[mask].mean() * 100 if mask.sum() > 0 else 0
    print(f"  In-sample  : filtered={mask.sum():,}  WR={wr:.1f}%  (expected — model saw this data)")
    print(f"  Deployment : 100% of {len(rows):,} rows used — no data held back")

    # Feature importance
    importances = model.feature_importances_
    ranked = sorted(zip(FEATURE_NAMES, importances), key=lambda x: -x[1])
    print("\n  Top-10 features:")
    for name, imp in ranked[:10]:
        bar = "█" * int(imp * 200)
        print(f"    {name:<22} {imp:.4f}  {bar}")

    # Save
    os.makedirs("service/model", exist_ok=True)
    joblib.dump(model, "service/model/trade_filter.pkl")
    meta = {
        "threshold": threshold,
        "features": FEATURE_NAMES,
        "trained_on": csv_path,
        "n_rows": len(rows),
        "training": "full_dataset",
    }
    with open("service/model/meta.json", "w") as f:
        json.dump(meta, f, indent=2)
    print("\nModel saved → service/model/trade_filter.pkl")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("csv_path")
    parser.add_argument("--threshold", type=float, default=0.70)
    args = parser.parse_args()
    train(args.csv_path, args.threshold)
