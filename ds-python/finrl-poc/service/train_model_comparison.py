#!/usr/bin/env python3
"""
Compare XGBoost, LightGBM, and CatBoost on impulse detection.
Uses the same data loading as train_impulse_model.py.
"""
import os
import glob
import numpy as np
import pandas as pd
from sklearn.metrics import classification_report, confusion_matrix
import pickle
import warnings
warnings.filterwarnings('ignore')

DATA_DIR = "/codes/algotrade/zerodha-algo-trading/elliott_train_data"
TF = "1h"
STRATEGY = "price-jump"
TEST_FRAC = 0.2
MODEL_DIR = "/codes/algotrade/zerodha-algo-trading/ds-python/finrl-poc/service/model"


def load_data():
    pattern = os.path.join(DATA_DIR, f"*_{TF}_{STRATEGY}_impulse.csv")
    files = sorted(glob.glob(pattern))
    print(f"Loading {len(files)} CSV files...")

    dfs = []
    for f in files:
        try:
            df = pd.read_csv(f)
            dfs.append(df)
        except Exception as e:
            print(f"  SKIP {os.path.basename(f)}: {e}")

    df = pd.concat(dfs, ignore_index=True)
    print(f"Total rows: {len(df)}")

    # Label encoding
    df['y'] = 0  # no_impulse
    df.loc[df['label'] == 'wave3_start', 'y'] = df.loc[df['label'] == 'wave3_start', 'direction'].apply(
        lambda d: 1 if d > 0 else 2)
    df.loc[df['label'] == 'wave5_start', 'y'] = df.loc[df['label'] == 'wave5_start', 'direction'].apply(
        lambda d: 1 if d > 0 else 2)

    print(f"Labels: {df['y'].value_counts().to_dict()}")

    # Feature columns (skip metadata)
    meta_cols = ['timestamp', 'price', 'label', 'direction', 'symbol', 'timeframe', 'bars_to_target']
    feature_cols = [c for c in df.columns if c not in meta_cols and c != 'y']
    print(f"Features: {len(feature_cols)}")

    # Chronological split
    df['ts_dt'] = pd.to_datetime(df['timestamp'])
    df = df.sort_values('ts_dt')

    # Downsample no_impulse to match impulse count
    impulse_count = len(df[df['y'] > 0])
    no_impulse = df[df['y'] == 0]
    impulse = df[df['y'] > 0]

    if len(no_impulse) > impulse_count:
        no_impulse_sampled = no_impulse.sample(n=impulse_count, random_state=42)
        df_balanced = pd.concat([no_impulse_sampled, impulse]).sort_values('ts_dt')
    else:
        df_balanced = df.sort_values('ts_dt')

    print(f"After balancing: {len(df_balanced)} rows, labels: {df_balanced['y'].value_counts().to_dict()}")

    # Split
    split_idx = int(len(df_balanced) * (1.0 - TEST_FRAC))
    train = df_balanced.iloc[:split_idx]
    test = df_balanced.iloc[split_idx:]

    X_train = train[feature_cols].replace([np.inf, -np.inf], np.nan).fillna(0).values
    y_train = train['y'].values
    X_test = test[feature_cols].replace([np.inf, -np.inf], np.nan).fillna(0).values
    y_test = test['y'].values

    print(f"Train: {len(X_train)} | Test: {len(X_test)}")
    print(f"Train date range: {train['ts_dt'].min()} to {train['ts_dt'].max()}")
    print(f"Test date range: {test['ts_dt'].min()} to {test['ts_dt'].max()}")

    return X_train, y_train, X_test, y_test, feature_cols


def train_xgboost(X_train, y_train, X_test, y_test):
    from xgboost import XGBClassifier

    print("\n" + "="*60)
    print("XGBoost")
    print("="*60)

    model = XGBClassifier(
        n_estimators=500,
        max_depth=6,
        learning_rate=0.05,
        subsample=0.8,
        colsample_bytree=0.8,
        min_child_weight=3,
        reg_alpha=0.1,
        reg_lambda=1.0,
        eval_metric='mlogloss',
        use_label_encoder=False,
        random_state=42,
        n_jobs=-1,
    )
    model.fit(X_train, y_train, eval_set=[(X_test, y_test)], verbose=False)

    y_pred = model.predict(X_test)
    print(classification_report(y_test, y_pred, target_names=['no_impulse', 'bullish', 'bearish']))
    print("Confusion Matrix:")
    print(confusion_matrix(y_test, y_pred))

    return model


def train_lightgbm(X_train, y_train, X_test, y_test):
    try:
        import lightgbm as lgb
    except ImportError:
        print("\nLightGBM not installed. Installing...")
        os.system("pip install lightgbm -q")
        import lightgbm as lgb

    print("\n" + "="*60)
    print("LightGBM")
    print("="*60)

    model = lgb.LGBMClassifier(
        n_estimators=500,
        max_depth=6,
        learning_rate=0.05,
        subsample=0.8,
        colsample_bytree=0.8,
        min_child_weight=3,
        reg_alpha=0.1,
        reg_lambda=1.0,
        num_leaves=63,
        random_state=42,
        n_jobs=-1,
        verbose=-1,
    )
    model.fit(X_train, y_train, eval_set=[(X_test, y_test)])

    y_pred = model.predict(X_test)
    print(classification_report(y_test, y_pred, target_names=['no_impulse', 'bullish', 'bearish']))
    print("Confusion Matrix:")
    print(confusion_matrix(y_test, y_pred))

    return model


def train_catboost(X_train, y_train, X_test, y_test):
    try:
        from catboost import CatBoostClassifier
    except ImportError:
        print("\nCatBoost not installed. Installing...")
        os.system("pip install catboost -q")
        from catboost import CatBoostClassifier

    print("\n" + "="*60)
    print("CatBoost")
    print("="*60)

    model = CatBoostClassifier(
        iterations=500,
        depth=6,
        learning_rate=0.05,
        l2_leaf_reg=3,
        random_seed=42,
        verbose=False,
        auto_class_weights='Balanced',
        eval_metric='MultiClass',
    )
    model.fit(X_train, y_train, eval_set=(X_test, y_test), verbose=False)

    y_pred = model.predict(X_test).flatten().astype(int)
    print(classification_report(y_test, y_pred, target_names=['no_impulse', 'bullish', 'bearish']))
    print("Confusion Matrix:")
    print(confusion_matrix(y_test, y_pred))

    return model


def train_xgboost_tuned(X_train, y_train, X_test, y_test):
    """XGBoost with aggressive regularization to prevent overfitting."""
    from xgboost import XGBClassifier

    print("\n" + "="*60)
    print("XGBoost (Tuned — more regularization)")
    print("="*60)

    # Compute class weights
    classes, counts = np.unique(y_train, return_counts=True)
    total = len(y_train)
    weights = {c: total / (len(classes) * cnt) for c, cnt in zip(classes, counts)}
    sample_weights = np.array([weights[y] for y in y_train])

    model = XGBClassifier(
        n_estimators=300,
        max_depth=4,           # shallower trees
        learning_rate=0.03,    # slower learning
        subsample=0.7,
        colsample_bytree=0.6, # use fewer features per tree
        min_child_weight=10,   # higher min samples per leaf
        reg_alpha=1.0,         # strong L1 regularization
        reg_lambda=5.0,        # strong L2 regularization
        gamma=1.0,             # minimum loss reduction
        eval_metric='mlogloss',
        use_label_encoder=False,
        random_state=42,
        n_jobs=-1,
    )
    model.fit(X_train, y_train, sample_weight=sample_weights,
              eval_set=[(X_test, y_test)], verbose=False)

    y_pred = model.predict(X_test)
    print(classification_report(y_test, y_pred, target_names=['no_impulse', 'bullish', 'bearish']))
    print("Confusion Matrix:")
    print(confusion_matrix(y_test, y_pred))

    return model


def main():
    X_train, y_train, X_test, y_test, feature_cols = load_data()

    results = {}

    # 1. XGBoost (default)
    xgb_model = train_xgboost(X_train, y_train, X_test, y_test)
    results['xgboost'] = xgb_model

    # 2. XGBoost (tuned)
    xgb_tuned = train_xgboost_tuned(X_train, y_train, X_test, y_test)
    results['xgboost_tuned'] = xgb_tuned

    # 3. LightGBM
    lgb_model = train_lightgbm(X_train, y_train, X_test, y_test)
    results['lightgbm'] = lgb_model

    # 4. CatBoost
    cb_model = train_catboost(X_train, y_train, X_test, y_test)
    results['catboost'] = cb_model

    # Save best model
    print("\n" + "="*60)
    print("SUMMARY")
    print("="*60)

    for name, model in results.items():
        y_pred = model.predict(X_test)
        from sklearn.metrics import accuracy_score, precision_score
        acc = accuracy_score(y_test, y_pred)
        # Precision for bullish (class 1)
        bull_mask = y_pred == 1
        bull_prec = precision_score(y_test[bull_mask], y_pred[bull_mask] == 1, zero_division=0) if bull_mask.sum() > 0 else 0
        # Actually compute properly
        prec_bull = precision_score(y_test, y_pred, labels=[1], average=None, zero_division=0)[0]
        prec_bear = precision_score(y_test, y_pred, labels=[2], average=None, zero_division=0)[0]
        print(f"  {name:20s}  acc={acc:.3f}  bull_prec={prec_bull:.3f}  bear_prec={prec_bear:.3f}")

    # Save all models
    for name, model in results.items():
        path = os.path.join(MODEL_DIR, f"impulse_{name}.pkl")
        with open(path, 'wb') as f:
            pickle.dump(model, f)
        print(f"  Saved: {path}")


if __name__ == '__main__':
    main()
