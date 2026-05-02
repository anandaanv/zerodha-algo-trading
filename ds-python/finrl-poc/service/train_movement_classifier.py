#!/usr/bin/env python3
"""
Movement bucket classifier — labels every pivot by the max price movement
in the next N bars, then trains a multi-class model.

Classes:
  0: big_bear   (< -7%)
  1: bear       (-7% to -4%)
  2: mild_bear  (-4% to -2%)
  3: neutral    (-2% to +2%)
  4: mild_bull  (+2% to +4%)
  5: bull       (+4% to +7%)
  6: big_bull   (> +7%)

For trading:
  LONG confidence  = P(bull) + P(big_bull)
  SHORT confidence = P(bear) + P(big_bear)
"""
import os, glob, argparse
import numpy as np
import pandas as pd
import pickle
from sklearn.metrics import classification_report, confusion_matrix
from xgboost import XGBClassifier

BUCKETS = [
    ('big_bear',  None, -7.0),
    ('bear',      -7.0, -4.0),
    ('mild_bear', -4.0, -2.0),
    ('neutral',   -2.0,  2.0),
    ('mild_bull',  2.0,  4.0),
    ('bull',       4.0,  7.0),
    ('big_bull',   7.0, None),
]
BUCKET_NAMES = [b[0] for b in BUCKETS]

LOOKAHEAD_BARS = 19  # ~3 trading days at 1h


def classify_movement(pct):
    """Classify a percentage move into a bucket index."""
    for i, (name, low, high) in enumerate(BUCKETS):
        if low is None and pct < high:
            return i
        if high is None and pct >= low:
            return i
        if low is not None and high is not None and low <= pct < high:
            return i
    return 3  # neutral fallback


def load_and_label(data_dir, tf, strategy):
    """Load CSVs and relabel with movement buckets."""
    pattern = os.path.join(data_dir, f"*_{tf}_{strategy}_impulse.csv")
    files = sorted(glob.glob(pattern))
    print(f"Loading {len(files)} files...")

    all_dfs = []
    for f in files:
        try:
            df = pd.read_csv(f)
            all_dfs.append(df)
        except Exception as e:
            print(f"  SKIP {os.path.basename(f)}: {e}")

    df = pd.concat(all_dfs, ignore_index=True)
    print(f"Total rows: {len(df)}")

    # We need to relabel based on actual price movement
    # The CSVs have 'price' column and we need to compute forward return
    # But the CSVs don't have the raw bar data — they have features at pivot points
    # We'll use the existing label + direction as a proxy, OR compute from the data

    # The CSVs have 'bars_to_target' which tells us how many bars to the 7% target
    # But we need the actual movement %, not just whether it hit 7%

    # For now: use the existing features + reclassify based on what we know
    # The 'label' column is: 'wave3_start' (7%+ move) or 'no_impulse'
    # The 'direction' column: 1 (bullish) or -1 (bearish) or 0 (no impulse)

    # Since we don't have the actual forward return in the CSV,
    # we'll need to compute it from the candle data.
    # For this first pass, let's create a simulated movement using
    # the leg_size features and label:

    # Actually, the better approach: compute forward returns directly from candle DB
    # But that requires the Java app. For now, let's use a simplified approach:
    # - wave3_start with direction=1 → big_bull (class 6) if bars_to_target is small, else bull (5)
    # - wave3_start with direction=-1 → big_bear (class 0) if bars_to_target is small, else bear (1)
    # - no_impulse → we need to estimate the actual movement

    # This is a ROUGH first pass. The proper solution is to compute actual
    # forward returns in the Java labeler and include them in the CSV.

    print("WARNING: Using proxy labels. For proper labels, the Java labeler needs to output actual movement %.")
    print("Assigning proxy classes based on existing labels + bars_to_target...")

    df['y'] = 3  # default: neutral

    # For wave3_start entries
    w3 = df['label'] == 'wave3_start'
    bull_w3 = w3 & (df['direction'] == 1)
    bear_w3 = w3 & (df['direction'] == -1)

    # Use bars_to_target to estimate magnitude
    # Fast moves (< 10 bars) → big move, slow moves → moderate move
    fast_bull = bull_w3 & (df['bars_to_target'].fillna(20) < 10)
    slow_bull = bull_w3 & (df['bars_to_target'].fillna(20) >= 10)
    fast_bear = bear_w3 & (df['bars_to_target'].fillna(20) < 10)
    slow_bear = bear_w3 & (df['bars_to_target'].fillna(20) >= 10)

    df.loc[fast_bull, 'y'] = 6  # big_bull
    df.loc[slow_bull, 'y'] = 5  # bull
    df.loc[fast_bear, 'y'] = 0  # big_bear
    df.loc[slow_bear, 'y'] = 1  # bear

    # For no_impulse, randomly assign to mild categories based on current indicators
    # Use curr_direction as a hint
    noimp = df['label'] == 'no_impulse'
    bullish_bias = noimp & (df.get('curr_direction', pd.Series(0, index=df.index)) > 0)
    bearish_bias = noimp & (df.get('curr_direction', pd.Series(0, index=df.index)) < 0)

    # Random assignment to mild categories
    np.random.seed(42)
    # Most no_impulse should be neutral (class 3), but some are mild_bull/mild_bear
    noimp_labels = np.random.choice([2, 3, 3, 3, 4], size=noimp.sum())
    df.loc[noimp, 'y'] = noimp_labels

    print(f"\nLabel distribution:")
    for i, name in enumerate(BUCKET_NAMES):
        count = (df['y'] == i).sum()
        print(f"  {i}: {name:12s} = {count:6d} ({count/len(df)*100:.1f}%)")

    return df


def load_and_label_from_db(data_dir, tf, strategy):
    """
    Proper labeling: compute actual forward returns from candle data.
    This requires DB access — use when the Java-side labeler doesn't provide movement %.
    """
    import mysql.connector

    pattern = os.path.join(data_dir, f"*_{tf}_{strategy}_impulse.csv")
    files = sorted(glob.glob(pattern))
    print(f"Loading {len(files)} files...")

    all_dfs = []
    for f in files:
        try:
            df = pd.read_csv(f)
            symbol = os.path.basename(f).split('_')[0]
            df['_symbol'] = symbol
            all_dfs.append(df)
        except:
            pass

    df = pd.concat(all_dfs, ignore_index=True)
    print(f"Total rows: {len(df)}")

    # Connect to DB and compute forward returns
    conn = mysql.connector.connect(host='localhost', database='algotrading',
                                    user='anand', password='password')
    cur = conn.cursor()

    # Get instrument tokens
    symbols = df['_symbol'].unique().tolist()
    placeholders = ','.join(['%s'] * len(symbols))
    cur.execute(f"SELECT tradingsymbol, instrument_token FROM instrument "
                f"WHERE tradingsymbol IN ({placeholders}) AND exchange = 'NSE' AND instrument_type = 'EQ'",
                symbols)
    token_map = {row[0]: row[1] for row in cur.fetchall()}

    print(f"Computing forward returns for {len(token_map)} symbols...")

    interval_map = {'1h': 'OneHour', '15m': 'FifteenMinute', '1d': 'Day'}
    db_tf = interval_map.get(tf, 'OneHour')

    # Process one symbol at a time to control memory
    bull_pcts = np.full(len(df), np.nan)
    bear_pcts = np.full(len(df), np.nan)
    net_pcts = np.full(len(df), np.nan)

    done = 0
    for symbol in symbols:
        token = token_map.get(symbol)
        if token is None:
            continue

        mask = df['_symbol'] == symbol
        if mask.sum() == 0:
            continue

        # Load candles for this symbol only
        cur.execute(
            "SELECT timestamp, high, low, close FROM candle "
            "WHERE instrument_instrument_token = %s AND timeframe = %s ORDER BY timestamp",
            (token, db_tf))
        candles = cur.fetchall()
        if not candles:
            continue

        # Build arrays
        ts_list = [str(c[0]).split('.')[0] for c in candles]
        high_arr = [float(c[1]) for c in candles]
        low_arr = [float(c[2]) for c in candles]
        close_arr = [float(c[3]) for c in candles]

        # Build index with multiple format keys
        ts_to_idx = {}
        for i, ts_str in enumerate(ts_list):
            ts_to_idx[ts_str] = i
            ts_to_idx[ts_str.replace(' ', 'T') + 'Z'] = i

        matched = 0
        indices = df.index[mask]
        for idx in indices:
            ts = str(df.at[idx, 'timestamp'])
            bar_idx = ts_to_idx.get(ts)
            if bar_idx is None:
                ts_clean = ts.replace('T', ' ').replace('Z', '').split('+')[0]
                bar_idx = ts_to_idx.get(ts_clean)
            if bar_idx is None:
                continue

            start_close = close_arr[bar_idx]
            if start_close <= 0:
                continue

            end_idx = min(bar_idx + LOOKAHEAD_BARS, len(candles) - 1)
            if end_idx <= bar_idx:
                continue

            max_high = max(high_arr[bar_idx+1:end_idx+1])
            min_low = min(low_arr[bar_idx+1:end_idx+1])
            end_close = close_arr[end_idx]

            bull_pcts[idx] = (max_high - start_close) / start_close * 100
            bear_pcts[idx] = (start_close - min_low) / start_close * 100
            net_pcts[idx] = (end_close - start_close) / start_close * 100
            matched += 1

        done += 1
        if done <= 10 or done % 25 == 0:
            print(f"  {done}/{len(symbols)} symbols... ({symbol}: {matched}/{mask.sum()} matched)")

        # Free memory
        del candles, ts_list, high_arr, low_arr, close_arr, ts_to_idx

    cur.close()
    conn.close()

    df['forward_bull_pct'] = bull_pcts
    df['forward_bear_pct'] = bear_pcts
    df['forward_net_pct'] = net_pcts

    # Classify based on max directional move
    df['max_move_pct'] = np.nan
    valid = df['forward_bull_pct'].notna() & df['forward_bear_pct'].notna()
    # Use the larger of bull/bear move, with sign
    bull_bigger = df['forward_bull_pct'] >= df['forward_bear_pct']
    df.loc[valid & bull_bigger, 'max_move_pct'] = df.loc[valid & bull_bigger, 'forward_bull_pct']
    df.loc[valid & ~bull_bigger, 'max_move_pct'] = -df.loc[valid & ~bull_bigger, 'forward_bear_pct']

    df['y'] = df['max_move_pct'].apply(lambda x: classify_movement(x) if pd.notna(x) else 3)

    # Drop rows without forward return data
    before = len(df)
    df = df[valid].copy()
    print(f"Dropped {before - len(df)} rows without forward return data")

    print(f"\nLabel distribution:")
    for i, name in enumerate(BUCKET_NAMES):
        count = (df['y'] == i).sum()
        print(f"  {i}: {name:12s} = {count:6d} ({count/len(df)*100:.1f}%)")

    return df


def train_and_evaluate(df, feat_cols, model_out, test_frac=0.2):
    """Train XGBoost and evaluate with threshold analysis."""
    df['ts'] = pd.to_datetime(df['timestamp'])
    df = df.sort_values('ts')

    split = int(len(df) * (1 - test_frac))
    train = df.iloc[:split]
    test = df.iloc[split:]

    X_train = train[feat_cols].replace([np.inf, -np.inf], np.nan).fillna(0).values
    y_train = train['y'].values
    X_test = test[feat_cols].replace([np.inf, -np.inf], np.nan).fillna(0).values
    y_test = test['y'].values

    print(f"\nTrain: {len(X_train)} ({train['ts'].min()} to {train['ts'].max()})")
    print(f"Test:  {len(X_test)} ({test['ts'].min()} to {test['ts'].max()})")
    print(f"Train labels: {dict(zip(*np.unique(y_train, return_counts=True)))}")
    print(f"Test labels:  {dict(zip(*np.unique(y_test, return_counts=True)))}")

    print("\nTraining XGBoost (7-class)...")
    model = XGBClassifier(
        n_estimators=300, max_depth=6, learning_rate=0.05,
        subsample=0.8, colsample_bytree=0.8, min_child_weight=3,
        eval_metric='mlogloss', random_state=42, n_jobs=4,
        num_class=7,
    )
    model.fit(X_train, y_train, verbose=False)

    y_pred = model.predict(X_test)
    print("\n=== Classification Report ===")
    print(classification_report(y_test, y_pred, target_names=BUCKET_NAMES, zero_division=0))

    # Trading-relevant analysis
    probs = model.predict_proba(X_test)
    # LONG confidence = P(bull) + P(big_bull) = P(class 5) + P(class 6)
    long_conf = probs[:, 5] + probs[:, 6]
    # SHORT confidence = P(bear) + P(big_bear) = P(class 0) + P(class 1)
    short_conf = probs[:, 0] + probs[:, 1]

    print("\n=== Trading Signal Analysis ===")
    for thr in [0.30, 0.40, 0.50, 0.60, 0.70, 0.80]:
        # LONG signals
        long_mask = long_conf >= thr
        if long_mask.sum() > 0:
            # A "correct" long signal = actual class is 4, 5, or 6 (mild_bull, bull, big_bull)
            actual_bull = np.isin(y_test[long_mask], [4, 5, 6])
            long_prec = actual_bull.sum() / long_mask.sum() * 100
        else:
            long_prec = 0

        # SHORT signals
        short_mask = short_conf >= thr
        if short_mask.sum() > 0:
            actual_bear = np.isin(y_test[short_mask], [0, 1, 2])
            short_prec = actual_bear.sum() / short_mask.sum() * 100
        else:
            short_prec = 0

        print(f"  thr={thr:.2f}: LONG={long_mask.sum():4d} ({long_prec:.0f}% prec) | "
              f"SHORT={short_mask.sum():4d} ({short_prec:.0f}% prec)")

    # Top features
    print("\n=== Top 15 Features ===")
    importances = model.feature_importances_
    ranked = sorted(zip(feat_cols, importances), key=lambda x: -x[1])
    for name, score in ranked[:15]:
        bar = '█' * int(score * 200)
        print(f"  {name:35s} {score:.4f} {bar}")

    # Save
    with open(model_out, 'wb') as f:
        pickle.dump(model, f)
    print(f"\nModel saved to {model_out}")

    return model


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", default="/codes/algotrade/zerodha-algo-trading/elliott_train_data")
    parser.add_argument("--tf", default="1h")
    parser.add_argument("--strategy", default="price-jump")
    parser.add_argument("--test-frac", type=float, default=0.2)
    parser.add_argument("--use-db", action="store_true", help="Compute forward returns from DB (proper labels)")
    parser.add_argument("--model-out", default=None)
    parser.add_argument("--max-files", type=int, default=0, help="Limit files for quick test (0=all)")
    args = parser.parse_args()

    if args.model_out is None:
        args.model_out = f"/codes/algotrade/zerodha-algo-trading/ds-python/finrl-poc/service/model/impulse_movement_7class.pkl"

    if args.use_db:
        df = load_and_label_from_db(args.data_dir, args.tf, args.strategy)
    else:
        df = load_and_label(args.data_dir, args.tf, args.strategy)

    meta = ['timestamp', 'price', 'label', 'direction', 'symbol', 'timeframe',
            'bars_to_target', 'y', '_symbol', 'forward_bull_pct', 'forward_bear_pct',
            'forward_net_pct', 'max_move_pct', 'ts']
    feat_cols = [c for c in df.columns if c not in meta]
    print(f"Features: {len(feat_cols)}")

    train_and_evaluate(df, feat_cols, args.model_out, args.test_frac)


if __name__ == '__main__':
    main()
