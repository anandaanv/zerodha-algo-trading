"""
Train impulse classifier v3 on the new clean training data.

Approach (per user's strategy):
1. Profile confirmed impulses by direction (bullish/bearish separately)
2. Filter dataset using impulse indicator profile (P25-P75 of top features)
3. EXCLUDE filter features from training to prevent leakage
4. Train XGBoost on filtered set
5. Temporal OOS: train <=2025, test 2026

Usage:
    python scripts/train_impulse_v3.py --data elliott_train_data/impulse_training_v3_all.csv
"""
import argparse
import os
import numpy as np
import pandas as pd
import joblib
from xgboost import XGBClassifier
from sklearn.metrics import classification_report


def train_direction(df, direction, direction_name, test_year=2026, n_filter_features=5):
    """Train model for one direction (bullish or bearish)."""
    
    data = df.copy()
    data['target'] = (data['label'] == direction).astype(int)
    
    total_impulses = data['target'].sum()
    print(f"\n{'='*70}")
    print(f"{direction_name}: {len(data):,} rows, {total_impulses} impulses ({total_impulses/len(data)*100:.1f}%)")
    print(f"{'='*70}")
    
    if total_impulses < 50:
        print("  Insufficient impulses, skipping")
        return None
    
    # Meta columns (not features)
    meta = ['timestamp', 'symbol', 'label', 'bars_to_target', 'move_size',
            'open', 'high', 'low', 'close', 'volume', 'target',
            'ema20', 'ema50', 'ema200', 'bb_upper', 'bb_mid', 'bb_lower',
            'atr', 'vol_sma20', 'macd_line', 'macd_signal']
    
    feature_cols = [c for c in data.columns if c not in meta and data[c].dtype in ['float64', 'int64', 'float32']]
    print(f"  Available features: {len(feature_cols)}")
    
    # STEP 1: Train initial model to find top features
    X_all = data[feature_cols].fillna(0).replace([np.inf, -np.inf], 0)
    y_all = data['target']
    
    scale = (y_all == 0).sum() / max((y_all == 1).sum(), 1)
    model0 = XGBClassifier(n_estimators=200, max_depth=4, scale_pos_weight=scale,
                           learning_rate=0.05, random_state=42, eval_metric='logloss',
                           verbosity=0)
    model0.fit(X_all, y_all)
    
    # Get top features
    imp = sorted(zip(feature_cols, model0.feature_importances_), key=lambda x: -x[1])
    top = [(f, v) for f, v in imp if v > 0.005]
    
    print(f"\n  Top features (>0.5% importance):")
    for feat, val in top[:15]:
        print(f"    {feat:<35} {val:.4f}")
    
    # STEP 2: Use top N features as FILTER
    filter_features = [f for f, v in top[:n_filter_features]]
    training_features = [c for c in feature_cols if c not in filter_features]
    
    print(f"\n  Filter features (top {n_filter_features}, excluded from training): {filter_features}")
    
    # Get impulse profile on filter features (P25-P75)
    impulses = data[data['target'] == 1]
    filter_ranges = {}
    for feat in filter_features:
        vals = impulses[feat].dropna()
        if len(vals) < 20: continue
        filter_ranges[feat] = (vals.quantile(0.25), vals.quantile(0.75))
    
    # Apply filter
    mask = pd.Series(True, index=data.index)
    for feat, (lo, hi) in filter_ranges.items():
        mask = mask & data[feat].notna() & (data[feat] >= lo) & (data[feat] <= hi)
    
    filtered = data[mask].copy()
    filt_imp = filtered['target'].sum()
    
    print(f"\n  Filter result: {len(data):,} → {len(filtered):,} ({len(filtered)/len(data)*100:.1f}%)")
    print(f"  Impulses retained: {filt_imp}/{total_impulses} ({filt_imp/total_impulses*100:.0f}%)")
    print(f"  Impulse ratio: {filt_imp/len(filtered)*100:.1f}%")
    
    if filt_imp < 30:
        print("  Too few impulses after filter")
        return None
    
    # STEP 3: Temporal split
    filtered['year'] = pd.to_datetime(filtered['timestamp']).dt.year
    train_data = filtered[filtered['year'] < test_year]
    test_data = filtered[filtered['year'] >= test_year]
    
    X_train = train_data[training_features].fillna(0).replace([np.inf, -np.inf], 0)
    y_train = train_data['target']
    X_test = test_data[training_features].fillna(0).replace([np.inf, -np.inf], 0)
    y_test = test_data['target']
    
    print(f"\n  Temporal split (train <{test_year}, test >={test_year}):")
    print(f"    Train: {len(X_train):,} ({y_train.sum()} impulses)")
    print(f"    Test:  {len(X_test):,} ({y_test.sum()} impulses)")
    
    if y_train.sum() < 20 or y_test.sum() < 5:
        print("  Insufficient data for temporal split")
        return None
    
    # STEP 4: Train final model
    scale = (y_train == 0).sum() / max((y_train == 1).sum(), 1)
    model = XGBClassifier(
        n_estimators=300, max_depth=4, scale_pos_weight=scale,
        learning_rate=0.03, subsample=0.8, colsample_bytree=0.8,
        min_child_weight=3, random_state=42, eval_metric='logloss',
        verbosity=0
    )
    model.fit(X_train, y_train)
    probs = model.predict_proba(X_test)[:, 1]
    
    print(f"\n  {'Thr':<6} {'Prec':>6} {'Recall':>8} {'Signals':>8} {'Wins':>6} {'FP':>6}")
    for thr in [0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9]:
        pred = (probs >= thr).astype(int)
        tp = ((pred == 1) & (y_test == 1)).sum()
        fp = ((pred == 1) & (y_test == 0)).sum()
        fn = ((pred == 0) & (y_test == 1)).sum()
        prec = tp / (tp + fp) if (tp + fp) > 0 else 0
        rec = tp / (tp + fn) if (tp + fn) > 0 else 0
        print(f"  {thr:<6} {prec:>5.0%} {rec:>8.0%} {tp+fp:>8} {tp:>6} {fp:>6}")
    
    # Top training features
    imp2 = sorted(zip(training_features, model.feature_importances_), key=lambda x: -x[1])
    print(f"\n  Top 10 training features (post-filter):")
    for feat, val in imp2[:10]:
        print(f"    {feat:<35} {val:.4f}")
    
    return {
        'model': model,
        'filter_features': filter_features,
        'filter_ranges': filter_ranges,
        'training_features': training_features,
        'direction': direction_name,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--data', required=True, help='Training CSV path')
    parser.add_argument('--test-year', type=int, default=2026)
    parser.add_argument('--n-filter', type=int, default=5, help='Number of top features to use as filter')
    parser.add_argument('--model-dir', default='strategies/impulse/models')
    args = parser.parse_args()
    
    print(f"Loading {args.data}...")
    df = pd.read_csv(args.data)
    print(f"Loaded: {len(df):,} rows, {df['symbol'].nunique()} stocks")
    print(f"Labels: {df['label'].value_counts().to_dict()}")
    
    # Train bullish (label=1) and bearish (label=2) separately
    for direction, name in [(1, 'BULLISH'), (2, 'BEARISH')]:
        result = train_direction(df, direction, name, args.test_year, args.n_filter)
        
        if result:
            model_path = f"{args.model_dir}/impulse_v3_{name.lower()}.pkl"
            os.makedirs(args.model_dir, exist_ok=True)
            joblib.dump(result, model_path)
            print(f"\n  Saved → {model_path}")


if __name__ == '__main__':
    main()
