"""
Train MFE/MAE/bars regression models to predict optimal target and stop placement.

Usage:
    python service/train_mfe_model.py /tmp/all_fno_combo_backtest.csv
"""
import argparse, json, os, sys
import numpy as np
import joblib

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from analysis.trade_filter import load_rows, extract_features, FEATURE_NAMES
from xgboost import XGBRegressor
from sklearn.model_selection import train_test_split
from sklearn.metrics import mean_squared_error, mean_absolute_error


def _safe_float(v, default=0.0):
    try:
        return float(v) if v not in (None, "", "null") else default
    except (ValueError, TypeError):
        return default


def train_mfe(csv_path: str):
    rows = load_rows(csv_path)

    # Filter out OPEN trades (no outcome yet) and rows missing MFE/MAE data
    rows = [r for r in rows if r.get("result") not in ("OPEN", "") and r.get("mfe_pct", "") != ""]
    print(f"Loaded {len(rows):,} rows with MFE/MAE data")

    X = np.array([extract_features(r) for r in rows])
    y_mfe  = np.array([_safe_float(r.get("mfe_pct", "0")) for r in rows])
    y_mae  = np.array([_safe_float(r.get("mae_pct", "0")) for r in rows])
    y_bars = np.array([_safe_float(r.get("mfe_bars", "0")) for r in rows])
    y_win  = np.array([1 if r["result"] == "WIN" else 0 for r in rows])

    # Stratified split (by WIN/LOSS) for consistent evaluation
    indices = np.arange(len(rows))
    train_idx, test_idx = train_test_split(indices, test_size=0.30, random_state=42, stratify=y_win)

    def _train_one(y_all, name):
        model = XGBRegressor(
            n_estimators=300, max_depth=4, learning_rate=0.05,
            subsample=0.8, colsample_bytree=0.8,
            eval_metric="rmse", verbosity=0,
        )
        model.fit(
            X[train_idx], y_all[train_idx],
            eval_set=[(X[test_idx], y_all[test_idx])],
            verbose=False,
        )
        preds = model.predict(X[test_idx])
        rmse = np.sqrt(mean_squared_error(y_all[test_idx], preds))
        mae  = mean_absolute_error(y_all[test_idx], preds)
        median_pred = float(np.median(preds))
        median_actual = float(np.median(y_all[test_idx]))
        print(f"  {name:<12}  RMSE={rmse:.3f}  MAE={mae:.3f}  median_pred={median_pred:.2f}  median_actual={median_actual:.2f}")
        return model, float(rmse), float(mae)

    print("\n=== Regression model performance on held-out 30% ===")
    mfe_model,  mfe_rmse,  mfe_mae_err  = _train_one(y_mfe,  "mfe_pct")
    mae_model,  mae_rmse,  mae_mae_err  = _train_one(y_mae,  "mae_pct")
    bars_model, bars_rmse, bars_mae_err = _train_one(y_bars, "mfe_bars")

    # Feature importance for MFE model
    importances = mfe_model.feature_importances_
    ranked = sorted(zip(FEATURE_NAMES, importances), key=lambda x: -x[1])
    print("\n  Top-10 features for MFE prediction:")
    for name, imp in ranked[:10]:
        bar = "█" * int(imp * 200)
        print(f"    {name:<22} {imp:.4f}  {bar}")

    os.makedirs("service/model", exist_ok=True)
    joblib.dump(mfe_model,  "service/model/mfe_model.pkl")
    joblib.dump(mae_model,  "service/model/mae_model.pkl")
    joblib.dump(bars_model, "service/model/bars_model.pkl")

    meta = {
        "features": FEATURE_NAMES,
        "trained_on": csv_path,
        "n_rows": len(rows),
        "mfe_pct":  {"rmse": mfe_rmse,  "mae": mfe_mae_err},
        "mae_pct":  {"rmse": mae_rmse,  "mae": mae_mae_err},
        "mfe_bars": {"rmse": bars_rmse, "mae": bars_mae_err},
    }
    with open("service/model/mfe_meta.json", "w") as f:
        json.dump(meta, f, indent=2)
    print("\nModels saved → service/model/mfe_model.pkl, mae_model.pkl, bars_model.pkl")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("csv_path", help="Combo backtest CSV with mfe_pct/mae_pct/mfe_bars columns")
    args = parser.parse_args()
    train_mfe(args.csv_path)
