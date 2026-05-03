"""
Train XGBoost on candidate-pivot training data.

3-class label (no_impulse=0, bullish=1, bearish=2) from label+direction columns.

Excluded as leak/metadata: timestamp, price, label, symbol, timeframe, direction,
y, bars_to_target, candidate_status, forward_*, entry_close, pivot_price.

candidate_pattern → one-hot encoded as `pat_<NAME>` features.

Usage:
    python service/train_candidate_pivot_model.py --tf 1h --max-bars-to-target 19
"""
import argparse, glob, os, sys, time
import numpy as np
import pandas as pd
import joblib
from xgboost import XGBClassifier
from sklearn.metrics import classification_report, confusion_matrix


def load_csvs(data_dir: str, tf: str):
    pattern = os.path.join(data_dir, f"*_{tf}_candidate_pivots.csv")
    files = sorted(glob.glob(pattern))
    if not files:
        print(f"ERROR: no files for pattern {pattern}", file=sys.stderr)
        sys.exit(1)
    dfs = []
    skipped = 0
    for f in files:
        with open(f) as fh:
            header = fh.readline().strip().split(",")
        if "candidate_pattern" not in header:
            skipped += 1
            continue
        dfs.append(pd.read_csv(f))
    if skipped:
        print(f"Skipped {skipped} files without candidate_pattern column (stale)")
    df = pd.concat(dfs, ignore_index=True)
    print(f"Loaded {len(dfs)} files, {len(df):,} total rows")
    return df


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tf", default="1h")
    ap.add_argument("--data-dir", default="../../candidate_pivot_train_data")
    ap.add_argument("--test-frac", type=float, default=0.2)
    ap.add_argument("--threshold", type=float, default=0.75)
    ap.add_argument("--max-bars-to-target", type=int, default=0,
                    help="Reclassify slow impulses as no_impulse (>N bars to target)")
    ap.add_argument("--model-out", default="service/model/candidate_pivot_xgb.pkl")
    args = ap.parse_args()

    df = load_csvs(args.data_dir, args.tf)

    df = df[df['label'].notna() & (df['label'] != '')]
    impulse_mask = df['label'].isin(['wave3_start', 'wave5_start'])
    df['direction'] = pd.to_numeric(df['direction'], errors='coerce').fillna(0).astype(int)
    df = df[~(impulse_mask & ~df['direction'].isin([1, -1]))].reset_index(drop=True)
    impulse_mask = df['label'].isin(['wave3_start', 'wave5_start'])
    df['y'] = 0
    df.loc[impulse_mask & (df['direction'] == 1), 'y'] = 1
    df.loc[impulse_mask & (df['direction'] == -1), 'y'] = 2

    n0, n1, n2 = (df['y'] == 0).sum(), (df['y'] == 1).sum(), (df['y'] == 2).sum()
    print(f"Pre-filter: {n0:,} no_impulse | {n1:,} bullish | {n2:,} bearish")

    if args.max_bars_to_target > 0:
        df['bars_to_target'] = pd.to_numeric(df['bars_to_target'], errors='coerce').fillna(-1)
        slow = impulse_mask & ((df['bars_to_target'] > args.max_bars_to_target) | (df['bars_to_target'] < 0))
        print(f"Slow-impulse reclass: {slow.sum():,} → no_impulse")
        df.loc[slow, 'y'] = 0
        n0, n1, n2 = (df['y'] == 0).sum(), (df['y'] == 1).sum(), (df['y'] == 2).sum()
        print(f"Post-filter: {n0:,} no_impulse | {n1:,} bullish | {n2:,} bearish")

    if 'candidate_status' in df.columns:
        print("By candidate_status × y:")
        print(pd.crosstab(df['candidate_status'], df['y']))

    # Feature column construction: drop metadata/leak, one-hot candidate_pattern
    exclude_cols = {'timestamp', 'price', 'label', 'symbol', 'timeframe',
                    'direction', 'y', 'bars_to_target', 'candidate_status',
                    'candidate_pattern',  # encoded separately
                    'forward_max_move_pct', 'forward_bull_pct', 'forward_bear_pct',
                    'bars_to_bull_max', 'bars_to_bear_max',
                    'entry_close', 'pivot_price', 'ts_dt'}

    if 'candidate_pattern' in df.columns:
        pattern_dummies = pd.get_dummies(df['candidate_pattern'].fillna('UNK'), prefix='pat')
        n_pat = pattern_dummies.shape[1]
        print(f"One-hot encoded candidate_pattern → {n_pat} cols ({list(pattern_dummies.columns)[:5]}...)")
        df = pd.concat([df, pattern_dummies], axis=1)
    else:
        n_pat = 0

    base_feature_cols = [c for c in df.columns if c not in exclude_cols and not c.startswith('pat_')]
    pat_feature_cols = [c for c in df.columns if c.startswith('pat_')]
    feature_cols = base_feature_cols + pat_feature_cols
    print(f"Using {len(base_feature_cols)} numeric + {len(pat_feature_cols)} pattern = {len(feature_cols)} features")

    # No downsampling — use sample weights to handle imbalance.
    counts = np.bincount(df['y'].values, minlength=3)
    print(f"Class counts: no_impulse={counts[0]:,} | bullish={counts[1]:,} | bearish={counts[2]:,}")

    df['ts_dt'] = pd.to_datetime(df['timestamp'])
    df = df.sort_values('ts_dt').reset_index(drop=True)

    X_df = df[feature_cols].apply(pd.to_numeric, errors='coerce').fillna(0)
    X_df = X_df.replace([np.inf, -np.inf], np.nan).fillna(0).clip(lower=-1e6, upper=1e6)
    X = X_df.values.astype(np.float32)
    y = df['y'].values

    split_idx = int(len(df) * (1.0 - args.test_frac))
    train_X, test_X = X[:split_idx], X[split_idx:]
    train_y, test_y = y[:split_idx], y[split_idx:]
    train_ts, test_ts = df['ts_dt'].iloc[:split_idx], df['ts_dt'].iloc[split_idx:]

    print(f"\nTrain {len(train_y):,} rows | {train_ts.min()} → {train_ts.max()}")
    print(f"Test  {len(test_y):,} rows | {test_ts.min()} → {test_ts.max()}")
    print(f"Train y: {np.bincount(train_y, minlength=3)}")
    print(f"Test  y: {np.bincount(test_y, minlength=3)}")
    # Balanced sample weights (sklearn-style): w_c = N / (K * n_c)
    train_counts = np.bincount(train_y, minlength=3).astype(float)
    K = (train_counts > 0).sum()
    N = train_counts.sum()
    cls_w = np.where(train_counts > 0, N / (K * train_counts), 0.0)
    sample_w = cls_w[train_y]
    print(f"Class weights: no_impulse={cls_w[0]:.3f} | bullish={cls_w[1]:.3f} | bearish={cls_w[2]:.3f}")

    print(f"\nTraining XGBoost ({len(feature_cols)} features)...")
    t0 = time.time()
    model = XGBClassifier(
        n_estimators=400, max_depth=6, learning_rate=0.05,
        subsample=0.8, colsample_bytree=0.8,
        objective='multi:softprob', num_class=3,
        tree_method='hist', n_jobs=-1, random_state=42, eval_metric='mlogloss'
    )
    model.fit(train_X, train_y, sample_weight=sample_w, eval_set=[(test_X, test_y)], verbose=False)
    print(f"Trained in {time.time() - t0:.1f}s")

    proba = model.predict_proba(test_X)
    pred = proba.argmax(axis=1)
    print("\nDefault (argmax) classification report:")
    print(classification_report(test_y, pred, labels=[0, 1, 2],
                                target_names=['no_impulse', 'bullish', 'bearish'], digits=3))
    print("Confusion (rows=actual, cols=predicted):")
    print(confusion_matrix(test_y, pred, labels=[0, 1, 2]))

    print(f"\n--- Threshold {args.threshold} ---")
    bullish_pred = proba[:, 1] >= args.threshold
    bearish_pred = proba[:, 2] >= args.threshold
    if bullish_pred.sum() > 0:
        prec = (bullish_pred & (test_y == 1)).sum() / bullish_pred.sum()
        print(f"Bullish ≥{args.threshold}: {bullish_pred.sum():,} signals | precision {prec:.3f}")
    if bearish_pred.sum() > 0:
        prec = (bearish_pred & (test_y == 2)).sum() / bearish_pred.sum()
        print(f"Bearish ≥{args.threshold}: {bearish_pred.sum():,} signals | precision {prec:.3f}")

    print("\nTop-30 features by importance:")
    importances = model.feature_importances_
    top = np.argsort(importances)[::-1][:30]
    for i in top:
        print(f"  {feature_cols[i]:40s} {importances[i]:.5f}")

    os.makedirs(os.path.dirname(args.model_out), exist_ok=True)
    joblib.dump({'model': model, 'feature_cols': feature_cols}, args.model_out)
    print(f"\nSaved → {args.model_out}")


if __name__ == "__main__":
    main()
