#!/usr/bin/env python3
"""
Stacked Ensemble Model — 8 specialist models + 1 meta model.

Each specialist sees a different slice of features but predicts the same
7-class movement target. The meta model learns which specialists to trust.

Groups:
  1. Price Action (candle patterns at curr/p1/p2)
  2. Momentum (RSI, Stoch, MACD at curr+p1-p8)
  3. Trend Position (EMA distances, trend states, market structure)
  4. Volatility Regime (BB width, BBW EMAs across TFs)
  5. Wave Structure (leg sizes, durations, retracements, trend segments)
  6. HTF Trend Strength (all daily TF features)
  7. LTF Micro-Turn (all 15min TF features)
  8. Directional Bias (ADX, +DI, -DI across pivots)

Meta model: 56 probabilities (8×7) → 7-class final prediction
"""
import os, glob, argparse, pickle, time
import numpy as np
import pandas as pd
from sklearn.metrics import classification_report
from xgboost import XGBClassifier
import mysql.connector

BUCKET_NAMES = ['big_bear', 'bear', 'mild_bear', 'neutral', 'mild_bull', 'bull', 'big_bull']
BUCKETS = [(-999, -7), (-7, -4), (-4, -2), (-2, 2), (2, 4), (4, 7), (7, 999)]
LOOKAHEAD = 19


def classify_movement(pct):
    for i, (lo, hi) in enumerate(BUCKETS):
        if lo <= pct < hi:
            return i
    return 3


def define_feature_groups(feat_cols):
    """Split feature columns into 8 specialist groups."""
    groups = {}

    # Helper: find columns matching patterns
    def find(patterns):
        result = []
        for col in feat_cols:
            for pat in patterns:
                if pat in col:
                    result.append(col)
                    break
        return list(dict.fromkeys(result))  # dedupe preserving order

    # Group 1: Price Action / Candle Patterns
    groups['price_action'] = find(['_cp_', 'curr_direction'])

    # Group 2: Momentum (RSI, Stoch, MACD — primary TF only, not HTF/LTF)
    momentum_patterns = ['_rsi', '_stoch_k', '_stoch_d', '_stoch_cross',
                         '_macd_line', '_macd_signal', '_macd_hist']
    groups['momentum'] = [c for c in find(momentum_patterns)
                          if not c.startswith('htf_') and not c.startswith('ltf_')]

    # Group 3: Trend Position (EMA distances, trend states, market structure)
    trend_patterns = ['_ema20_dist', '_ema50_dist', '_ema200_dist', '_trend_state',
                      'mkt_trend_state', 'mkt_trend_streak', 'mkt_bars_since_reversal',
                      'mkt_last_break_type']
    groups['trend'] = [c for c in find(trend_patterns)
                       if not c.startswith('htf_') and not c.startswith('ltf_')]

    # Group 4: Volatility Regime (BB features across all TFs)
    vol_patterns = ['_bb_position', '_bb_bandwidth', '_bbw_ema',
                    'leg_size_vs_avg', 'leg_size_stddev', 'legs_contracting']
    groups['volatility'] = find(vol_patterns)

    # Group 5: Wave Structure (leg metrics + trend segments)
    wave_patterns = ['_leg_size_pct', '_leg_duration', '_retrace_pct', '_leg_speed',
                     'bars_since_large_leg', 'max_retrace_last10', 'min_retrace_last10',
                     'leg_duration_vs_avg', 'trend1_', 'trend2_', 'trend3_', 'trend4_',
                     'trend5_', 'trend6_', 'trend7_', 'trend8_', 'trend9_', 'trend10_']
    # Exclude HTF/LTF
    groups['wave'] = [c for c in find(wave_patterns)
                      if not c.startswith('htf_') and not c.startswith('ltf_')]

    # Group 6: HTF Trend Strength (all HTF features)
    groups['htf'] = [c for c in feat_cols if c.startswith('htf_')]

    # Group 7: LTF Micro-Turn (all LTF features)
    groups['ltf'] = [c for c in feat_cols if c.startswith('ltf_')]

    # Group 8: Directional Bias (ADX + DI)
    di_patterns = ['_adx', '_plus_di', '_minus_di']
    groups['direction'] = [c for c in find(di_patterns)
                           if not c.startswith('htf_') and not c.startswith('ltf_')]

    return groups


def load_and_label(data_dir):
    """Load CSVs and compute forward return labels from DB."""
    files = sorted(glob.glob(os.path.join(data_dir, '*_1h_price-jump_impulse.csv')))
    # Only load files with max columns (381 features = 388 cols)
    files_381 = [f for f in files if len(open(f).readline().strip().split(',')) == 388]
    if not files_381:
        # Fall back to whatever we have
        files_381 = files
    print(f"Loading {len(files_381)} files...")

    dfs = [pd.read_csv(f) for f in files_381]
    df = pd.concat(dfs, ignore_index=True)
    print(f"Total: {len(df)} rows")

    meta = ['timestamp', 'price', 'label', 'direction', 'symbol', 'timeframe', 'bars_to_target']
    feat_cols = [c for c in df.columns if c not in meta]
    print(f"Features: {len(feat_cols)}")

    # Compute forward returns from DB
    conn = mysql.connector.connect(host='localhost', database='algotrading',
                                    user='anand', password='password')
    cur = conn.cursor()
    symbols = df['symbol'].unique().tolist()
    placeholders = ','.join(['%s'] * len(symbols))
    cur.execute(f"SELECT tradingsymbol, instrument_token FROM instrument "
                f"WHERE tradingsymbol IN ({placeholders}) AND exchange='NSE' AND instrument_type='EQ'",
                symbols)
    token_map = {r[0]: r[1] for r in cur.fetchall()}
    print(f"Computing forward returns for {len(token_map)} symbols...")

    bull_pcts = np.full(len(df), np.nan)
    bear_pcts = np.full(len(df), np.nan)

    done = 0
    for sym in symbols:
        tok = token_map.get(sym)
        if not tok:
            continue
        mask = df['symbol'] == sym
        cur.execute("SELECT timestamp, high, low, close FROM candle "
                    "WHERE instrument_instrument_token = %s AND timeframe = %s ORDER BY timestamp",
                    (tok, 'OneHour'))
        candles = cur.fetchall()
        if not candles:
            continue

        ts2i = {}
        for i, c in enumerate(candles):
            k = str(c[0]).split('.')[0]
            ts2i[k] = i
            ts2i[k.replace(' ', 'T') + 'Z'] = i

        ha = [float(c[1]) for c in candles]
        la = [float(c[2]) for c in candles]
        ca = [float(c[3]) for c in candles]

        for idx in df.index[mask]:
            ts = str(df.at[idx, 'timestamp'])
            bi = ts2i.get(ts) or ts2i.get(ts.replace('T', ' ').replace('Z', '').split('+')[0])
            if bi is None:
                continue
            sc = ca[bi]
            end = min(bi + LOOKAHEAD, len(candles) - 1)
            if sc <= 0 or end <= bi:
                continue
            bull_pcts[idx] = (max(ha[bi+1:end+1]) - sc) / sc * 100
            bear_pcts[idx] = (sc - min(la[bi+1:end+1])) / sc * 100

        done += 1
        if done % 50 == 0:
            print(f"  {done}/{len(symbols)}")
        del candles, ts2i, ha, la, ca

    cur.close()
    conn.close()

    # Label
    valid = ~np.isnan(bull_pcts) & ~np.isnan(bear_pcts)
    max_move = np.where(bull_pcts >= bear_pcts, bull_pcts, -bear_pcts)
    df['y'] = [classify_movement(m) if v else 3 for m, v in zip(max_move, valid)]
    df = df[valid].copy()
    print(f"Labeled: {len(df)} rows")
    for i, name in enumerate(BUCKET_NAMES):
        c = (df['y'] == i).sum()
        print(f"  {i}: {name:12s} = {c:6d} ({c/len(df)*100:.1f}%)")

    return df, feat_cols


def train_specialist(name, X_train, y_train, X_test, y_test):
    """Train a single specialist model. Returns model and test probabilities."""
    model = XGBClassifier(
        n_estimators=150, max_depth=4, learning_rate=0.05,
        subsample=0.7, colsample_bytree=0.8, min_child_weight=5,
        eval_metric='mlogloss', random_state=42, n_jobs=1, num_class=7
    )
    model.fit(X_train, y_train, verbose=False)

    # Get probabilities for meta-model training
    train_probs = model.predict_proba(X_train)
    test_probs = model.predict_proba(X_test)

    # Quick eval
    y_pred = model.predict(X_test)
    from sklearn.metrics import accuracy_score
    acc = accuracy_score(y_test, y_pred)

    # Trading signal precision at 0.50 threshold
    long_conf = test_probs[:, 5] + test_probs[:, 6]
    short_conf = test_probs[:, 0] + test_probs[:, 1]
    lm = long_conf >= 0.50
    sm = short_conf >= 0.50
    lp = np.isin(y_test[lm], [4, 5, 6]).sum() / lm.sum() * 100 if lm.sum() > 0 else 0
    sp = np.isin(y_test[sm], [0, 1, 2]).sum() / sm.sum() * 100 if sm.sum() > 0 else 0

    print(f"  {name:20s} feats={X_train.shape[1]:3d}  acc={acc:.2f}  "
          f"LONG@0.5={lm.sum():4d}({lp:.0f}%)  SHORT@0.5={sm.sum():4d}({sp:.0f}%)")

    return model, train_probs, test_probs


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", default="/codes/algotrade/zerodha-algo-trading/elliott_train_data")
    parser.add_argument("--model-dir", default="/codes/algotrade/zerodha-algo-trading/ds-python/finrl-poc/service/model")
    parser.add_argument("--test-frac", type=float, default=0.2)
    args = parser.parse_args()

    # Load data
    df, feat_cols = load_and_label(args.data_dir)

    # Define feature groups
    groups = define_feature_groups(feat_cols)
    print(f"\n=== Feature Groups ===")
    total_feats = 0
    for name, cols in groups.items():
        print(f"  {name:20s}: {len(cols)} features")
        total_feats += len(cols)
    print(f"  {'TOTAL':20s}: {total_feats} (some shared)")

    # Chronological split
    df['ts'] = pd.to_datetime(df['timestamp'])
    df = df.sort_values('ts')
    split = int(len(df) * (1 - args.test_frac))
    train_df = df.iloc[:split]
    test_df = df.iloc[split:]
    y_train = train_df['y'].values
    y_test = test_df['y'].values
    print(f"\nTrain: {len(train_df)} ({train_df['ts'].min()} to {train_df['ts'].max()})")
    print(f"Test:  {len(test_df)} ({test_df['ts'].min()} to {test_df['ts'].max()})")

    # Train specialists
    print(f"\n=== Training 8 Specialist Models ===")
    specialist_models = {}
    meta_train_features = []
    meta_test_features = []

    for group_name, group_cols in groups.items():
        if not group_cols:
            print(f"  {group_name:20s} SKIPPED (no features)")
            continue

        X_tr = train_df[group_cols].replace([np.inf, -np.inf], np.nan).fillna(0).values
        X_te = test_df[group_cols].replace([np.inf, -np.inf], np.nan).fillna(0).values

        model, train_probs, test_probs = train_specialist(group_name, X_tr, y_train, X_te, y_test)
        specialist_models[group_name] = model
        meta_train_features.append(train_probs)
        meta_test_features.append(test_probs)

    # Build meta-model input: stack all specialist probabilities
    X_meta_train = np.hstack(meta_train_features)
    X_meta_test = np.hstack(meta_test_features)
    print(f"\n=== Meta Model ===")
    print(f"Meta features: {X_meta_train.shape[1]} ({len(specialist_models)} specialists × 7 classes)")

    # Train meta model
    meta_model = XGBClassifier(
        n_estimators=200, max_depth=4, learning_rate=0.05,
        subsample=0.8, colsample_bytree=0.8, min_child_weight=3,
        eval_metric='mlogloss', random_state=42, n_jobs=1, num_class=7
    )
    meta_model.fit(X_meta_train, y_train, verbose=False)

    # Evaluate meta model
    y_pred = meta_model.predict(X_meta_test)
    print(f"\n=== Meta Model Classification Report ===")
    print(classification_report(y_test, y_pred, target_names=BUCKET_NAMES, zero_division=0))

    # Trading signal analysis
    meta_probs = meta_model.predict_proba(X_meta_test)
    long_conf = meta_probs[:, 5] + meta_probs[:, 6]
    short_conf = meta_probs[:, 0] + meta_probs[:, 1]

    print("=== Stacked Ensemble Trading Signals ===")
    for thr in [0.30, 0.40, 0.50, 0.60, 0.70, 0.80]:
        lm = long_conf >= thr
        sm = short_conf >= thr
        lp = np.isin(y_test[lm], [4, 5, 6]).sum() / lm.sum() * 100 if lm.sum() > 0 else 0
        sp = np.isin(y_test[sm], [0, 1, 2]).sum() / sm.sum() * 100 if sm.sum() > 0 else 0
        print(f"  thr={thr:.2f}: LONG={lm.sum():5d} ({lp:.0f}% prec) | "
              f"SHORT={sm.sum():5d} ({sp:.0f}% prec)")

    # Compare with flat model
    print("\n=== Flat Model (381 features, same data) ===")
    X_flat_tr = train_df[feat_cols].replace([np.inf, -np.inf], np.nan).fillna(0).values
    X_flat_te = test_df[feat_cols].replace([np.inf, -np.inf], np.nan).fillna(0).values
    flat_model = XGBClassifier(
        n_estimators=200, max_depth=5, learning_rate=0.05,
        subsample=0.7, colsample_bytree=0.7, min_child_weight=3,
        eval_metric='mlogloss', random_state=42, n_jobs=1, num_class=7
    )
    flat_model.fit(X_flat_tr, y_train, verbose=False)
    flat_probs = flat_model.predict_proba(X_flat_te)
    flat_long = flat_probs[:, 5] + flat_probs[:, 6]
    flat_short = flat_probs[:, 0] + flat_probs[:, 1]
    for thr in [0.30, 0.40, 0.50, 0.60, 0.70, 0.80]:
        lm = flat_long >= thr
        sm = flat_short >= thr
        lp = np.isin(y_test[lm], [4, 5, 6]).sum() / lm.sum() * 100 if lm.sum() > 0 else 0
        sp = np.isin(y_test[sm], [0, 1, 2]).sum() / sm.sum() * 100 if sm.sum() > 0 else 0
        print(f"  thr={thr:.2f}: LONG={lm.sum():5d} ({lp:.0f}% prec) | "
              f"SHORT={sm.sum():5d} ({sp:.0f}% prec)")

    # Meta model feature importance (which specialists matter)
    print("\n=== Meta Model — Specialist Importance ===")
    imp = meta_model.feature_importances_
    group_names = list(specialist_models.keys())
    for i, gname in enumerate(group_names):
        group_imp = imp[i*7:(i+1)*7].sum()
        print(f"  {gname:20s}: {group_imp:.4f}")

    # Save everything
    ensemble = {
        'specialists': specialist_models,
        'meta_model': meta_model,
        'groups': {k: v for k, v in groups.items() if v},
        'group_order': list(specialist_models.keys()),
        'bucket_names': BUCKET_NAMES,
    }
    path = os.path.join(args.model_dir, 'impulse_stacked_ensemble.pkl')
    with open(path, 'wb') as f:
        pickle.dump(ensemble, f)
    print(f"\nEnsemble saved to {path}")


if __name__ == '__main__':
    main()
