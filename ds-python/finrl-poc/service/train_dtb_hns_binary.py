"""
Binary classifier for DTB+HNS training data: win (1) vs loss (0).

Mirrors train_candidate_pivot_binary.py but for DTB/HNS patterns.

Data flow:
1. Load CSV output from DtbHnsTrainingDataService (Java extractor)
2. Binary classification: label = 1 if pattern won (target hit first), else 0
3. Train set: rows with timestamp < 2022-01-01
4. Test set (OOS): rows with timestamp >= 2024-01-01
5. Drop rows between 2022-01-01 and 2024-01-01 (gap)
6. Train XGBoost with scale_pos_weight for class imbalance
7. Output: model.json (XGBoost model) + metrics.json (AUC, accuracy, precision, recall)
"""

import argparse
import glob
import json
import os
import sys
import time

import numpy as np
import pandas as pd
import joblib
from xgboost import XGBClassifier
from sklearn.metrics import classification_report, confusion_matrix, roc_auc_score, accuracy_score, precision_score, recall_score


def load_csvs(data_dir):
    """Load all DTB+HNS training CSVs from directory."""
    pattern = os.path.join(data_dir, "*_dtbhns_*.csv")
    files = sorted(glob.glob(pattern))
    if not files:
        # Try alternative pattern
        pattern = os.path.join(data_dir, "*.csv")
        files = sorted(glob.glob(pattern))

    dfs = []
    for f in files:
        try:
            df = pd.read_csv(f, nrows=1)  # Check header
            if 'label' in df.columns or 'label' in open(f).readline():
                dfs.append(pd.read_csv(f))
                print(f"Loaded {f}: {len(dfs[-1])} rows")
        except Exception as e:
            print(f"Skipped {f}: {e}")

    if not dfs:
        raise ValueError(f"No DTB+HNS CSV files found in {data_dir}")

    df = pd.concat(dfs, ignore_index=True)
    print(f"Total: {len(dfs)} files, {len(df):,} rows")
    return df


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="../../dtbhns_train_data")
    ap.add_argument("--test-frac", type=float, default=0.2)
    ap.add_argument("--model-out", default="service/model/dtb_hns_binary_xgb.pkl")
    ap.add_argument("--metrics-out", default="service/model/dtb_hns_metrics.json")
    args = ap.parse_args()

    print(f"Loading CSVs from {args.data_dir}...")
    df = load_csvs(args.data_dir)

    # Ensure label column exists and is numeric
    if 'label' not in df.columns:
        raise ValueError("CSV must contain 'label' column")

    df['y'] = pd.to_numeric(df['label'], errors='coerce').fillna(0).astype(int)
    n0, n1 = (df['y'] == 0).sum(), (df['y'] == 1).sum()
    print(f"Class counts: loss={n0:,} | win={n1:,} ({100*n1/(n0+n1):.1f}%)")

    # Ensure timestamp is datetime
    if 'timestamp' in df.columns:
        df['ts_dt'] = pd.to_datetime(df['timestamp'], errors='coerce')
    else:
        raise ValueError("CSV must contain 'timestamp' column")

    # Train/test split by date: train < 2022-01-01, test >= 2024-01-01 (gap in between)
    train_cutoff = pd.Timestamp("2022-01-01", tz="UTC")
    test_start = pd.Timestamp("2024-01-01", tz="UTC")

    df_train = df[df['ts_dt'] < train_cutoff].reset_index(drop=True)
    df_test = df[df['ts_dt'] >= test_start].reset_index(drop=True)

    print(f"\nTrain set: {len(df_train):,} rows | {df_train['ts_dt'].min()} → {df_train['ts_dt'].max()}")
    print(f"Test set (OOS): {len(df_test):,} rows | {df_test['ts_dt'].min()} → {df_test['ts_dt'].max()}")
    print(f"Train pos rate: {df_train['y'].mean():.3f} | Test pos rate: {df_test['y'].mean():.3f}")

    # Build feature list (exclude metadata)
    exclude_cols = {
        'timestamp', 'ts_dt', 'price', 'label', 'y', 'symbol', 'timeframe',
        'pattern_type', 'direction', 'entry_bar_idx'
    }

    feature_cols = [c for c in df.columns if c not in exclude_cols]
    print(f"Features: {len(feature_cols)} columns")

    # Extract features and target
    X_train = df_train[feature_cols].apply(pd.to_numeric, errors='coerce').fillna(0)
    X_train = X_train.replace([np.inf, -np.inf], 0).clip(-1e6, 1e6).values.astype(np.float32)
    y_train = df_train['y'].values

    X_test = df_test[feature_cols].apply(pd.to_numeric, errors='coerce').fillna(0)
    X_test = X_test.replace([np.inf, -np.inf], 0).clip(-1e6, 1e6).values.astype(np.float32)
    y_test = df_test['y'].values

    if len(X_train) == 0 or len(X_test) == 0:
        raise ValueError("Train or test set is empty after filtering")

    # Class weight for imbalance
    pos_w = (y_train == 0).sum() / max((y_train == 1).sum(), 1)
    print(f"scale_pos_weight = {pos_w:.3f}")

    # Train XGBoost
    print(f"\nTraining XGBoost...")
    t0 = time.time()
    model = XGBClassifier(
        n_estimators=400,
        max_depth=6,
        learning_rate=0.05,
        subsample=0.8,
        colsample_bytree=0.8,
        objective='binary:logistic',
        tree_method='hist',
        n_jobs=-1,
        random_state=42,
        eval_metric='auc',
        scale_pos_weight=pos_w,
    )
    model.fit(X_train, y_train, eval_set=[(X_test, y_test)], verbose=False)
    elapsed = time.time() - t0
    print(f"Trained in {elapsed:.1f}s")

    # Evaluate
    y_train_pred = model.predict(X_train)
    y_train_proba = model.predict_proba(X_train)[:, 1]

    y_test_pred = model.predict(X_test)
    y_test_proba = model.predict_proba(X_test)[:, 1]

    train_auc = roc_auc_score(y_train, y_train_proba)
    train_acc = accuracy_score(y_train, y_train_pred)
    train_prec = precision_score(y_train, y_train_pred, zero_division=0)
    train_rec = recall_score(y_train, y_train_pred, zero_division=0)

    test_auc = roc_auc_score(y_test, y_test_proba)
    test_acc = accuracy_score(y_test, y_test_pred)
    test_prec = precision_score(y_test, y_test_pred, zero_division=0)
    test_rec = recall_score(y_test, y_test_pred, zero_division=0)

    print(f"\n=== TRAIN METRICS ===")
    print(f"AUC:       {train_auc:.4f}")
    print(f"Accuracy:  {train_acc:.4f}")
    print(f"Precision: {train_prec:.4f}")
    print(f"Recall:    {train_rec:.4f}")

    print(f"\n=== TEST (OOS) METRICS ===")
    print(f"AUC:       {test_auc:.4f}")
    print(f"Accuracy:  {test_acc:.4f}")
    print(f"Precision: {test_prec:.4f}")
    print(f"Recall:    {test_rec:.4f}")

    # Threshold sweep
    print("\nThreshold sweep (precision = of flagged wins, how many actually won):")
    print(f"{'Thr':<7} {'Signals':<9} {'Precision':<11} {'Recall':<8} {'PosRate':<9}")
    for thr in [0.3, 0.4, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9]:
        pred = y_test_proba >= thr
        n = pred.sum()
        if n == 0:
            print(f"  {thr:<5} {n:<9} -        -      -")
            continue
        prec = (pred & (y_test == 1)).sum() / n
        rec = (pred & (y_test == 1)).sum() / max((y_test == 1).sum(), 1)
        print(f"  {thr:<5} {n:<9} {prec:<11.3f} {rec:<8.3f} {n/len(y_test):<9.3f}")

    # Feature importance
    print("\nTop-30 features by importance:")
    importances = model.feature_importances_
    top = np.argsort(importances)[::-1][:30]
    for i in top:
        if i < len(feature_cols):
            print(f"  {feature_cols[i]:40s} {importances[i]:.5f}")

    # Save model
    os.makedirs(os.path.dirname(args.model_out), exist_ok=True)
    joblib.dump({'model': model, 'feature_cols': feature_cols}, args.model_out)
    print(f"\nModel saved → {args.model_out}")

    # Save metrics
    metrics = {
        'train': {
            'auc': float(train_auc),
            'accuracy': float(train_acc),
            'precision': float(train_prec),
            'recall': float(train_rec),
        },
        'test': {
            'auc': float(test_auc),
            'accuracy': float(test_acc),
            'precision': float(test_prec),
            'recall': float(test_rec),
        },
        'class_counts': {
            'train_loss': int((y_train == 0).sum()),
            'train_win': int((y_train == 1).sum()),
            'test_loss': int((y_test == 0).sum()),
            'test_win': int((y_test == 1).sum()),
        },
        'scale_pos_weight': float(pos_w),
        'n_features': len(feature_cols),
        'trained_at': pd.Timestamp.now().isoformat(),
    }

    os.makedirs(os.path.dirname(args.metrics_out), exist_ok=True)
    with open(args.metrics_out, 'w') as f:
        json.dump(metrics, f, indent=2)
    print(f"Metrics saved → {args.metrics_out}")


if __name__ == "__main__":
    main()
