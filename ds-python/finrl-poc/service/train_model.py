"""
Train trade filter model and save to disk.

Usage:
    python service/train_model.py /tmp/all_fno_combo_backtest.csv
    python service/train_model.py /tmp/all_fno_combo_backtest.csv --threshold 0.82
"""
import argparse, json, os, sys
import numpy as np
import joblib

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from analysis.trade_filter import load_rows, extract_features, FEATURE_NAMES
from xgboost import XGBClassifier

def train(csv_path: str, threshold: float):
    rows = load_rows(csv_path)
    rows = sorted(rows, key=lambda r: r["datetime"])
    X = np.array([extract_features(r) for r in rows])
    y = np.array([1 if r["result"] == "WIN" else 0 for r in rows])

    split = int(len(rows) * 0.70)
    model = XGBClassifier(
        n_estimators=300, max_depth=4, learning_rate=0.05,
        subsample=0.8, colsample_bytree=0.8,
        eval_metric="logloss", verbosity=0,
    )
    model.fit(X[:split], y[:split], eval_set=[(X[split:], y[split:])], verbose=False)

    proba = model.predict_proba(X)[:, 1]
    mask  = proba >= threshold
    wr    = y[mask].mean() * 100 if mask.sum() > 0 else 0
    print(f"Trained on {len(rows):,} rows  |  threshold={threshold}  |  filtered={mask.sum():,}  |  WR={wr:.1f}%")

    os.makedirs("service/model", exist_ok=True)
    joblib.dump(model, "service/model/trade_filter.pkl")
    meta = {"threshold": threshold, "features": FEATURE_NAMES, "trained_on": csv_path, "n_rows": len(rows)}
    with open("service/model/meta.json", "w") as f:
        json.dump(meta, f, indent=2)
    print("Model saved → service/model/trade_filter.pkl")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("csv_path")
    parser.add_argument("--threshold", type=float, default=0.82)
    args = parser.parse_args()
    train(args.csv_path, args.threshold)
