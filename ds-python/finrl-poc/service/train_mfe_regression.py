"""
MFE regression baseline per impulse_filtering_modeling_strategy doc §7.

Two regressors trained per run:
  - bull_mfe: predicts forward_bull_pct  (max bullish forward move %)
  - bear_mfe: predicts forward_bear_pct  (max bearish forward move %)

NOTE: forward_*_pct in CSV is max within 38-bar lookahead WITHOUT 0.5×ATR
pullback. Treat this as a proxy for MFE_38 — proper MFE with pullback rule
needs a CSV regen and is a follow-up.

Metrics (doc §8.4, §9.1):
  - RMSE / MAE
  - Spearman correlation (PRIMARY — must be ≥ 0.15 for trading)
  - Top-decile mean MFE
  - Hit rate at threshold (predicted ≥ X → actual ≥ X)

Usage:
  python service/train_mfe_regression.py --tf 1h
  python service/train_mfe_regression.py --tf 1h --since 2022-01-01
"""
import argparse, glob, os, sys, time, warnings
import numpy as np, pandas as pd
import joblib
import lightgbm as lgb
from scipy.stats import spearmanr
from sklearn.metrics import mean_squared_error, mean_absolute_error

warnings.filterwarnings("ignore")

DATA_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "..", "candidate_pivot_train_data")
EXCLUDE = {
    "timestamp","price","label","symbol","timeframe","direction","candidate_status","bars_to_target",
    "forward_max_move_pct","forward_bull_pct","forward_bear_pct","bars_to_bull_max","bars_to_bear_max",
    "entry_close","pivot_price",
}


def load(tf, since=None):
    pattern = os.path.join(DATA_DIR, f"*_{tf}_candidate_pivots.csv")
    files = sorted(glob.glob(pattern))
    if not files:
        print(f"ERROR: no CSVs at {pattern}", file=sys.stderr); sys.exit(1)
    df = pd.concat([pd.read_csv(f) for f in files], ignore_index=True)
    df["ts_dt"] = pd.to_datetime(df["timestamp"])
    if since:
        s = pd.to_datetime(since, utc=True)
        if df["ts_dt"].dt.tz is None:
            df["ts_dt"] = df["ts_dt"].dt.tz_localize("UTC")
        df = df[df["ts_dt"] >= s].reset_index(drop=True)
    df = df.sort_values("ts_dt").reset_index(drop=True)
    print(f"Loaded {len(files)} files, {len(df):,} rows | range {df['ts_dt'].min()} → {df['ts_dt'].max()}")
    return df


def prepare(df):
    for c in ("forward_bull_pct","forward_bear_pct","forward_max_move_pct"):
        df[c] = pd.to_numeric(df[c], errors="coerce").fillna(0)
    fcols = [c for c in df.columns if c not in EXCLUDE and c != "ts_dt"]
    X = df[fcols].apply(pd.to_numeric, errors="coerce").fillna(0)
    X = X.replace([np.inf,-np.inf], np.nan).fillna(0).clip(lower=-1e6, upper=1e6)
    return df, X.values.astype(np.float32), fcols


def chrono_split(df, X, y, test_frac=0.15, val_frac=0.15):
    n = len(df)
    test_idx = int(n * (1 - test_frac))
    val_idx = int(n * (1 - test_frac - val_frac))
    return (X[:val_idx], y[:val_idx],
            X[val_idx:test_idx], y[val_idx:test_idx],
            X[test_idx:], y[test_idx:],
            df["ts_dt"].iloc[:val_idx], df["ts_dt"].iloc[val_idx:test_idx], df["ts_dt"].iloc[test_idx:])


def evaluate(name, y_true, y_pred):
    rmse = float(np.sqrt(mean_squared_error(y_true, y_pred)))
    mae = float(mean_absolute_error(y_true, y_pred))
    sp = spearmanr(y_true, y_pred).correlation
    # Top-decile MFE: avg actual MFE on top 10% predicted
    n = len(y_pred)
    top_n = max(1, n // 10)
    top_idx = np.argsort(y_pred)[-top_n:]
    td_pred_mean = float(np.mean(y_pred[top_idx]))
    td_actual_mean = float(np.mean(y_true[top_idx]))
    overall_actual = float(np.mean(y_true))
    print(f"  [{name}] n={n:,}  RMSE={rmse:.3f}  MAE={mae:.3f}  Spearman={sp:+.3f}  "
          f"top-decile pred={td_pred_mean:+.2f}%  actual={td_actual_mean:+.2f}%  "
          f"(overall mean={overall_actual:+.2f}%, lift={td_actual_mean/overall_actual:.1f}× if positive)")
    return {"rmse":rmse, "mae":mae, "spearman":sp, "top10_pred":td_pred_mean,
            "top10_actual":td_actual_mean, "overall":overall_actual}


def hit_rate(y_true, y_pred, thresholds=(2, 3, 5, 7)):
    print("  hit-rate at predicted thresholds:")
    for thr in thresholds:
        mask = y_pred >= thr
        n = mask.sum()
        if n == 0:
            print(f"    pred ≥ {thr}%: 0 signals")
            continue
        actual_mean = np.mean(y_true[mask])
        actual_ge_thr = np.mean(y_true[mask] >= thr)
        print(f"    pred ≥ {thr}%: {n:,} signals  actual_mean={actual_mean:+.2f}%  "
              f"hit-rate(actual≥{thr}%)={actual_ge_thr:.3f}")


def train_one(side, X_tr, y_tr, X_val, y_val, X_te, y_te, fcols, args):
    print(f"\n--- {side.upper()} regressor ---")
    # Clip target at 99th percentile (doc §8.1)
    p99 = float(np.quantile(y_tr, 0.99))
    print(f"  clipping target at p99={p99:.2f}%")
    y_tr_c = np.minimum(y_tr, p99)
    y_val_c = np.minimum(y_val, p99)

    model = lgb.LGBMRegressor(
        objective="regression",
        metric="rmse",
        n_estimators=args.n_estimators,
        learning_rate=args.lr,
        num_leaves=args.num_leaves,
        min_child_samples=args.min_child,
        reg_alpha=0.1,
        reg_lambda=0.1,
        feature_fraction=0.85,
        bagging_fraction=0.85,
        bagging_freq=5,
        random_state=42,
        n_jobs=-1,
        verbose=-1,
    )
    t0 = time.time()
    model.fit(X_tr, y_tr_c, eval_set=[(X_val, y_val_c)],
              callbacks=[lgb.early_stopping(50), lgb.log_evaluation(0)])
    print(f"  trained in {time.time()-t0:.1f}s  best_iter={model.best_iteration_}")

    # Eval on uncapped y so metrics reflect real-world targets
    pred_val = model.predict(X_val)
    pred_te = model.predict(X_te)
    evaluate("VAL ", y_val, pred_val)
    evaluate("TEST", y_te, pred_te)
    hit_rate(y_te, pred_te)

    # Top features
    imp = pd.DataFrame({"feat":fcols, "imp":model.feature_importances_}).sort_values("imp", ascending=False)
    print("  top 15 features:")
    for _, r in imp.head(15).iterrows():
        print(f"    {r['feat']:30s} {int(r['imp'])}")

    return model, pred_te


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tf", default="1h")
    ap.add_argument("--since", default=None, help="Filter rows ts >= ISO date e.g. 2022-01-01")
    ap.add_argument("--n-estimators", type=int, default=500)
    ap.add_argument("--lr", type=float, default=0.05)
    ap.add_argument("--num-leaves", type=int, default=31)
    ap.add_argument("--min-child", type=int, default=50)
    ap.add_argument("--out-prefix", default="mfe_lgbm")
    args = ap.parse_args()

    df = load(args.tf, args.since)
    df, X, fcols = prepare(df)
    print(f"Features: {len(fcols)}")

    # Two targets
    y_bull = df["forward_bull_pct"].values.astype(np.float32)
    y_bear = df["forward_bear_pct"].values.astype(np.float32)

    print(f"\nbull target stats: mean={y_bull.mean():.2f}%  median={np.median(y_bull):.2f}%  p90={np.quantile(y_bull,0.9):.2f}%")
    print(f"bear target stats: mean={y_bear.mean():.2f}%  median={np.median(y_bear):.2f}%  p90={np.quantile(y_bear,0.9):.2f}%")

    # Chrono split (70/15/15)
    n = len(df)
    val_idx = int(n*0.70)
    test_idx = int(n*0.85)
    X_tr, X_val, X_te = X[:val_idx], X[val_idx:test_idx], X[test_idx:]
    ts_tr, ts_val, ts_te = df["ts_dt"].iloc[:val_idx], df["ts_dt"].iloc[val_idx:test_idx], df["ts_dt"].iloc[test_idx:]
    print(f"\nTrain n={len(X_tr):,}  {ts_tr.min()} → {ts_tr.max()}")
    print(f"Val   n={len(X_val):,}  {ts_val.min()} → {ts_val.max()}")
    print(f"Test  n={len(X_te):,}  {ts_te.min()} → {ts_te.max()}")

    model_bull, pred_bull = train_one("bull", X_tr, y_bull[:val_idx], X_val, y_bull[val_idx:test_idx], X_te, y_bull[test_idx:], fcols, args)
    model_bear, pred_bear = train_one("bear", X_tr, y_bear[:val_idx], X_val, y_bear[val_idx:test_idx], X_te, y_bear[test_idx:], fcols, args)

    # Save
    out_dir = os.path.join(os.path.dirname(__file__), "model")
    os.makedirs(out_dir, exist_ok=True)
    suffix = f"_{args.tf}"
    if args.since: suffix += f"_since{args.since.replace('-','')}"
    bull_path = os.path.join(out_dir, f"{args.out_prefix}_bull{suffix}.pkl")
    bear_path = os.path.join(out_dir, f"{args.out_prefix}_bear{suffix}.pkl")
    joblib.dump(model_bull, bull_path); joblib.dump(model_bear, bear_path)
    print(f"\nSaved → {bull_path}")
    print(f"Saved → {bear_path}")

    # Combined direction picker — predicted side that's larger
    pred_max = np.maximum(pred_bull, pred_bear)
    pred_dir = np.where(pred_bull >= pred_bear, 1, -1)
    actual_dir_move = np.where(pred_dir == 1, y_bull[test_idx:], y_bear[test_idx:])
    print(f"\n--- Combined picker (max(pred_bull, pred_bear)) ---")
    for thr in (2, 3, 5, 7):
        mask = pred_max >= thr
        n = mask.sum()
        if n == 0:
            print(f"  pred_max ≥ {thr}%: 0"); continue
        avg_actual = np.mean(actual_dir_move[mask])
        hit = np.mean(actual_dir_move[mask] >= thr)
        print(f"  pred_max ≥ {thr}%: {n:,} signals  avg_actual_picked={avg_actual:+.2f}%  hit-rate≥{thr}%={hit:.3f}")


if __name__ == "__main__":
    main()
