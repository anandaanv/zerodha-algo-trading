"""
Train one binary XGBoost per candle pattern. Compare AUC and precision-at-threshold
to see which patterns are individually predictive.
"""
import argparse, glob, os
import numpy as np
import pandas as pd
import joblib
from xgboost import XGBClassifier
from sklearn.metrics import roc_auc_score


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tf", default="1h")
    ap.add_argument("--data-dir", default="../../candidate_pivot_train_data")
    ap.add_argument("--min-samples", type=int, default=2000)
    ap.add_argument("--test-frac", type=float, default=0.2)
    ap.add_argument("--model-dir", default="service/model/per_pattern")
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(args.data_dir, f"*_{args.tf}_candidate_pivots.csv")))
    dfs = []
    for f in files:
        with open(f) as fh:
            if 'candidate_pattern' not in fh.readline():
                continue
        dfs.append(pd.read_csv(f))
    df = pd.concat(dfs, ignore_index=True)
    df = df[df['label'].notna() & (df['label'] != '')].reset_index(drop=True)
    df['y'] = df['label'].isin(['wave3_start', 'wave5_start']).astype(int)
    df['ts_dt'] = pd.to_datetime(df['timestamp'])
    print(f"Loaded {len(df):,} rows")

    exclude_cols = {'timestamp', 'price', 'label', 'symbol', 'timeframe',
                    'direction', 'y', 'bars_to_target', 'candidate_status',
                    'candidate_pattern',
                    'forward_max_move_pct', 'forward_bull_pct', 'forward_bear_pct',
                    'bars_to_bull_max', 'bars_to_bear_max',
                    'entry_close', 'pivot_price', 'ts_dt'}
    feat_cols = [c for c in df.columns if c not in exclude_cols]
    print(f"Features: {len(feat_cols)}")

    os.makedirs(args.model_dir, exist_ok=True)

    print()
    header = f"{'Pattern':<26} {'N':<7} {'PosRate':<8} {'AUC':<7} {'P@0.6':<8} {'N@0.6':<7} {'P@0.7':<8} {'N@0.7':<7} {'P@0.8':<8} {'N@0.8':<7}"
    print(header)
    print("-" * len(header))

    pat_counts = df['candidate_pattern'].value_counts()
    rows = []
    for pat in pat_counts.index:
        n_total = pat_counts[pat]
        if n_total < args.min_samples:
            continue
        sub = df[df['candidate_pattern'] == pat].sort_values('ts_dt').reset_index(drop=True)
        X = sub[feat_cols].apply(pd.to_numeric, errors='coerce').fillna(0)
        X = X.replace([np.inf, -np.inf], 0).clip(-1e6, 1e6).values.astype(np.float32)
        y = sub['y'].values
        split = int(len(sub) * (1 - args.test_frac))
        train_X, test_X = X[:split], X[split:]
        train_y, test_y = y[:split], y[split:]
        if (train_y == 1).sum() < 50 or (test_y == 1).sum() < 20:
            continue
        pos_w = (train_y == 0).sum() / max((train_y == 1).sum(), 1)
        model = XGBClassifier(
            n_estimators=300, max_depth=5, learning_rate=0.05,
            subsample=0.8, colsample_bytree=0.8,
            objective='binary:logistic', tree_method='hist',
            n_jobs=-1, random_state=42, eval_metric='auc',
            scale_pos_weight=pos_w,
        )
        model.fit(train_X, train_y, verbose=False)
        proba = model.predict_proba(test_X)[:, 1]
        auc = roc_auc_score(test_y, proba) if (test_y.sum() > 0 and (test_y == 0).sum() > 0) else float('nan')

        def pat_threshold(thr):
            pred = proba >= thr
            n = pred.sum()
            if n == 0:
                return ('-', 0)
            prec = (pred & (test_y == 1)).sum() / n
            return (f"{prec:.3f}", int(n))

        p06, n06 = pat_threshold(0.6)
        p07, n07 = pat_threshold(0.7)
        p08, n08 = pat_threshold(0.8)
        pos_rate = test_y.mean()
        print(f"{pat:<26} {len(sub):<7} {pos_rate:<8.3f} {auc:<7.3f} {p06:<8} {n06:<7} {p07:<8} {n07:<7} {p08:<8} {n08:<7}")

        joblib.dump({'model': model, 'feature_cols': feat_cols, 'pattern': pat}, os.path.join(args.model_dir, f'{pat}.pkl'))
        rows.append((pat, len(sub), pos_rate, auc, p06, n06, p07, n07, p08, n08))

    print(f"\nSaved {len(rows)} models to {args.model_dir}")


if __name__ == "__main__":
    main()
