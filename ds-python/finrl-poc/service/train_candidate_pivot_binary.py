"""
Binary classifier on candidate-pivot data: impulse (1) vs no_impulse (0).
Direction is inferred at inference time from the candle pattern (BULLISH_*
patterns → bullish trade, BEARISH_* → bearish).

No downsampling, no slow-impulse filter, sample-weighted to handle imbalance.
"""
import argparse, glob, os, sys, time
import numpy as np
import pandas as pd
import joblib
from xgboost import XGBClassifier
from sklearn.metrics import classification_report, confusion_matrix, roc_auc_score


def load_csvs(data_dir, tf):
    pattern = os.path.join(data_dir, f"*_{tf}_candidate_pivots.csv")
    files = sorted(glob.glob(pattern))
    dfs = []
    for f in files:
        with open(f) as fh:
            if 'candidate_pattern' not in fh.readline():
                continue
        dfs.append(pd.read_csv(f))
    df = pd.concat(dfs, ignore_index=True)
    print(f"Loaded {len(dfs)} files, {len(df):,} total rows")
    return df


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tf", default="1h")
    ap.add_argument("--data-dir", default="../../candidate_pivot_train_data")
    ap.add_argument("--test-frac", type=float, default=0.2)
    ap.add_argument("--model-out", default="service/model/candidate_pivot_binary_xgb.pkl")
    args = ap.parse_args()

    df = load_csvs(args.data_dir, args.tf)
    df = df[df['label'].notna() & (df['label'] != '')].reset_index(drop=True)

    df['y'] = df['label'].isin(['wave3_start', 'wave5_start']).astype(int)
    n0, n1 = (df['y'] == 0).sum(), (df['y'] == 1).sum()
    print(f"Class counts: no_impulse={n0:,} | impulse={n1:,} ({100*n1/(n0+n1):.1f}%)")

    if 'candidate_status' in df.columns:
        print("By candidate_status × y:")
        print(pd.crosstab(df['candidate_status'], df['y']))

    exclude_cols = {'timestamp', 'price', 'label', 'symbol', 'timeframe',
                    'direction', 'y', 'bars_to_target', 'candidate_status',
                    'candidate_pattern',
                    'forward_max_move_pct', 'forward_bull_pct', 'forward_bear_pct',
                    'bars_to_bull_max', 'bars_to_bear_max',
                    'entry_close', 'pivot_price', 'ts_dt'}

    pat_dummies = pd.get_dummies(df['candidate_pattern'].fillna('UNK'), prefix='pat')
    n_pat = pat_dummies.shape[1]
    print(f"One-hot encoded candidate_pattern → {n_pat} cols")
    df = pd.concat([df, pat_dummies], axis=1)

    base_cols = [c for c in df.columns if c not in exclude_cols and not c.startswith('pat_')]
    pat_cols = [c for c in df.columns if c.startswith('pat_')]
    feature_cols = base_cols + pat_cols
    print(f"Features: {len(base_cols)} numeric + {len(pat_cols)} pattern = {len(feature_cols)}")

    df['ts_dt'] = pd.to_datetime(df['timestamp'])
    df = df.sort_values('ts_dt').reset_index(drop=True)

    X = df[feature_cols].apply(pd.to_numeric, errors='coerce').fillna(0)
    X = X.replace([np.inf, -np.inf], 0).clip(-1e6, 1e6).values.astype(np.float32)
    y = df['y'].values

    split = int(len(df) * (1 - args.test_frac))
    train_X, test_X = X[:split], X[split:]
    train_y, test_y = y[:split], y[split:]
    train_ts, test_ts = df['ts_dt'].iloc[:split], df['ts_dt'].iloc[split:]

    print(f"\nTrain {len(train_y):,} | {train_ts.min()} → {train_ts.max()}")
    print(f"Test  {len(test_y):,} | {test_ts.min()} → {test_ts.max()}")
    print(f"Train pos rate: {train_y.mean():.3f}  | Test pos rate: {test_y.mean():.3f}")

    pos_w = (train_y == 0).sum() / max((train_y == 1).sum(), 1)
    print(f"scale_pos_weight = {pos_w:.3f}")

    model = XGBClassifier(
        n_estimators=400, max_depth=6, learning_rate=0.05,
        subsample=0.8, colsample_bytree=0.8,
        objective='binary:logistic',
        tree_method='hist', n_jobs=-1, random_state=42,
        eval_metric='auc', scale_pos_weight=pos_w,
    )
    t0 = time.time()
    model.fit(train_X, train_y, eval_set=[(test_X, test_y)], verbose=False)
    print(f"Trained in {time.time() - t0:.1f}s")

    proba = model.predict_proba(test_X)[:, 1]
    auc = roc_auc_score(test_y, proba)
    print(f"\nTest AUC: {auc:.4f}")

    print("\nThreshold sweep (precision = of all flagged-impulse, how many really are impulse):")
    print(f"{'Thr':<7} {'Signals':<9} {'Precision':<11} {'Recall':<8} {'PosRate':<9}")
    for thr in [0.3, 0.4, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9]:
        pred = proba >= thr
        n = pred.sum()
        if n == 0:
            print(f"  {thr:<5} {n:<9} -        -      -")
            continue
        prec = (pred & (test_y == 1)).sum() / n
        rec  = (pred & (test_y == 1)).sum() / max((test_y == 1).sum(), 1)
        print(f"  {thr:<5} {n:<9} {prec:<11.3f} {rec:<8.3f} {n/len(test_y):<9.3f}")

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
