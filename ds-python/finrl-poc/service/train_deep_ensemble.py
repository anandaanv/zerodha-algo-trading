#!/usr/bin/env python3
"""
Deep Stacked Ensemble — 3 layers + combined scoring with flat model.

Layer 0: 8 specialists (feature groups → 7 probs each = 56)
Layer 1: 3 concept models (specialist probs → 7 probs each = 21)
  - Market Regime = Wave + Volatility + HTF specialists
  - Entry Timing = Price Action + Momentum + LTF specialists
  - Direction Strength = Trend + Direction specialists
Layer 2: Final meta model (21 concept probs → 7 class prediction)

Combined Score: Only signal when BOTH deep ensemble AND flat model agree.
"""
import os, glob, argparse, pickle
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
    groups = {}
    def find(patterns):
        result = []
        for col in feat_cols:
            for pat in patterns:
                if pat in col:
                    result.append(col)
                    break
        return list(dict.fromkeys(result))

    groups['price_action'] = find(['_cp_', 'curr_direction'])
    groups['momentum'] = [c for c in find(['_rsi', '_stoch_k', '_stoch_d', '_stoch_cross',
                          '_macd_line', '_macd_signal', '_macd_hist'])
                          if not c.startswith('htf_') and not c.startswith('ltf_')]
    groups['trend'] = [c for c in find(['_ema20_dist', '_ema50_dist', '_ema200_dist', '_trend_state',
                       'mkt_trend_state', 'mkt_trend_streak', 'mkt_bars_since_reversal', 'mkt_last_break_type'])
                       if not c.startswith('htf_') and not c.startswith('ltf_')]
    groups['volatility'] = find(['_bb_position', '_bb_bandwidth', '_bbw_ema',
                                  'leg_size_vs_avg', 'leg_size_stddev', 'legs_contracting'])
    groups['wave'] = [c for c in find(['_leg_size_pct', '_leg_duration', '_retrace_pct', '_leg_speed',
                      'bars_since_large_leg', 'max_retrace_last10', 'min_retrace_last10',
                      'leg_duration_vs_avg', 'trend1_', 'trend2_', 'trend3_', 'trend4_',
                      'trend5_', 'trend6_', 'trend7_', 'trend8_', 'trend9_', 'trend10_'])
                      if not c.startswith('htf_') and not c.startswith('ltf_')]
    groups['htf'] = [c for c in feat_cols if c.startswith('htf_')]
    groups['ltf'] = [c for c in feat_cols if c.startswith('ltf_')]
    groups['direction'] = [c for c in find(['_adx', '_plus_di', '_minus_di'])
                           if not c.startswith('htf_') and not c.startswith('ltf_')]
    return groups


# Layer 1 concept groupings — which specialists feed into which concepts
CONCEPTS = {
    'market_regime': ['wave', 'volatility', 'htf'],        # What's the market doing?
    'entry_timing':  ['price_action', 'momentum', 'ltf'],  # Is now the right time?
    'direction_strength': ['trend', 'direction'],           # Which way and how strong?
}


def load_and_label(data_dir):
    files = sorted(glob.glob(os.path.join(data_dir, '*_1h_price-jump_impulse.csv')))
    files_381 = [f for f in files if len(open(f).readline().strip().split(',')) == 388]
    if not files_381:
        files_381 = files
    print(f"Loading {len(files_381)} files...")
    dfs = [pd.read_csv(f) for f in files_381]
    df = pd.concat(dfs, ignore_index=True)
    print(f"Total: {len(df)} rows")

    meta = ['timestamp', 'price', 'label', 'direction', 'symbol', 'timeframe', 'bars_to_target']
    feat_cols = [c for c in df.columns if c not in meta]
    print(f"Features: {len(feat_cols)}")

    conn = mysql.connector.connect(host='localhost', database='algotrading',
                                    user='anand', password='password')
    cur = conn.cursor()
    symbols = df['symbol'].unique().tolist()
    placeholders = ','.join(['%s'] * len(symbols))
    cur.execute(f"SELECT tradingsymbol, instrument_token FROM instrument "
                f"WHERE tradingsymbol IN ({placeholders}) AND exchange='NSE' AND instrument_type='EQ'", symbols)
    token_map = {r[0]: r[1] for r in cur.fetchall()}
    print(f"Computing forward returns for {len(token_map)} symbols...")

    bull_pcts = np.full(len(df), np.nan)
    bear_pcts = np.full(len(df), np.nan)
    done = 0
    for sym in symbols:
        tok = token_map.get(sym)
        if not tok: continue
        mask = df['symbol'] == sym
        cur.execute("SELECT timestamp, high, low, close FROM candle "
                    "WHERE instrument_instrument_token = %s AND timeframe = %s ORDER BY timestamp",
                    (tok, 'OneHour'))
        candles = cur.fetchall()
        if not candles: continue
        ts2i = {}
        for i, c in enumerate(candles):
            k = str(c[0]).split('.')[0]; ts2i[k] = i; ts2i[k.replace(' ', 'T') + 'Z'] = i
        ha = [float(c[1]) for c in candles]; la = [float(c[2]) for c in candles]; ca = [float(c[3]) for c in candles]
        for idx in df.index[mask]:
            ts = str(df.at[idx, 'timestamp'])
            bi = ts2i.get(ts) or ts2i.get(ts.replace('T', ' ').replace('Z', '').split('+')[0])
            if bi is None: continue
            sc = ca[bi]; end = min(bi + LOOKAHEAD, len(candles) - 1)
            if sc <= 0 or end <= bi: continue
            bull_pcts[idx] = (max(ha[bi+1:end+1]) - sc) / sc * 100
            bear_pcts[idx] = (sc - min(la[bi+1:end+1])) / sc * 100
        done += 1
        if done % 50 == 0: print(f"  {done}/{len(symbols)}")
        del candles, ts2i, ha, la, ca
    cur.close(); conn.close()

    valid = ~np.isnan(bull_pcts) & ~np.isnan(bear_pcts)
    max_move = np.where(bull_pcts >= bear_pcts, bull_pcts, -bear_pcts)
    df['y'] = [classify_movement(m) if v else 3 for m, v in zip(max_move, valid)]
    df = df[valid].copy()
    print(f"Labeled: {len(df)} rows")
    for i, name in enumerate(BUCKET_NAMES):
        c = (df['y'] == i).sum()
        print(f"  {i}: {name:12s} = {c:6d} ({c/len(df)*100:.1f}%)")
    return df, feat_cols


def train_model(name, X_tr, y_tr, n_est=150, depth=4, n_jobs=1):
    model = XGBClassifier(n_estimators=n_est, max_depth=depth, learning_rate=0.05,
                           subsample=0.7, colsample_bytree=0.8, min_child_weight=5,
                           eval_metric='mlogloss', random_state=42, n_jobs=n_jobs, num_class=7)
    model.fit(X_tr, y_tr, verbose=False)
    return model


def eval_trading(probs, y_test, label=""):
    long_conf = probs[:, 5] + probs[:, 6]
    short_conf = probs[:, 0] + probs[:, 1]
    print(f"\n=== {label} Trading Signals ===")
    for thr in [0.30, 0.40, 0.50, 0.60, 0.70, 0.80]:
        lm = long_conf >= thr; sm = short_conf >= thr
        lp = np.isin(y_test[lm], [4, 5, 6]).sum() / lm.sum() * 100 if lm.sum() > 0 else 0
        sp = np.isin(y_test[sm], [0, 1, 2]).sum() / sm.sum() * 100 if sm.sum() > 0 else 0
        print(f"  thr={thr:.2f}: LONG={lm.sum():5d} ({lp:.0f}% prec) | SHORT={sm.sum():5d} ({sp:.0f}% prec)")
    return long_conf, short_conf


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", default="/codes/algotrade/zerodha-algo-trading/elliott_train_data")
    parser.add_argument("--model-dir", default="/codes/algotrade/zerodha-algo-trading/ds-python/finrl-poc/service/model")
    parser.add_argument("--test-frac", type=float, default=0.2)
    args = parser.parse_args()

    df, feat_cols = load_and_label(args.data_dir)
    groups = define_feature_groups(feat_cols)

    print(f"\n=== Feature Groups ===")
    for name, cols in groups.items():
        print(f"  {name:20s}: {len(cols)} features")

    df['ts'] = pd.to_datetime(df['timestamp'])
    df = df.sort_values('ts')
    split = int(len(df) * (1 - args.test_frac))
    train_df = df.iloc[:split]
    test_df = df.iloc[split:]
    y_train = train_df['y'].values
    y_test = test_df['y'].values
    print(f"\nTrain: {len(train_df)} ({train_df['ts'].min()} to {train_df['ts'].max()})")
    print(f"Test:  {len(test_df)} ({test_df['ts'].min()} to {test_df['ts'].max()})")

    # ═══════════════════════════════════════════════
    # LAYER 0: Train 8 specialists
    # ═══════════════════════════════════════════════
    print(f"\n{'='*60}")
    print(f"LAYER 0: Training 8 Specialists")
    print(f"{'='*60}")

    l0_models = {}
    l0_train_probs = {}
    l0_test_probs = {}

    for gname, gcols in groups.items():
        if not gcols: continue
        X_tr = train_df[gcols].replace([np.inf, -np.inf], np.nan).fillna(0).values
        X_te = test_df[gcols].replace([np.inf, -np.inf], np.nan).fillna(0).values
        model = train_model(gname, X_tr, y_train)
        l0_models[gname] = model
        l0_train_probs[gname] = model.predict_proba(X_tr)
        l0_test_probs[gname] = model.predict_proba(X_te)

        # Quick eval
        from sklearn.metrics import accuracy_score
        acc = accuracy_score(y_test, model.predict(X_te))
        lc = l0_test_probs[gname][:, 5] + l0_test_probs[gname][:, 6]
        sc = l0_test_probs[gname][:, 0] + l0_test_probs[gname][:, 1]
        lm = lc >= 0.5; sm = sc >= 0.5
        lp = np.isin(y_test[lm], [4, 5, 6]).sum() / lm.sum() * 100 if lm.sum() > 0 else 0
        sp = np.isin(y_test[sm], [0, 1, 2]).sum() / sm.sum() * 100 if sm.sum() > 0 else 0
        print(f"  {gname:20s} feats={len(gcols):3d} acc={acc:.2f} L@.5={lm.sum():4d}({lp:.0f}%) S@.5={sm.sum():4d}({sp:.0f}%)")

    # ═══════════════════════════════════════════════
    # LAYER 1: Train 3 concept models
    # ═══════════════════════════════════════════════
    print(f"\n{'='*60}")
    print(f"LAYER 1: Training 3 Concept Models")
    print(f"{'='*60}")

    l1_models = {}
    l1_train_probs = {}
    l1_test_probs = {}

    for concept_name, specialist_names in CONCEPTS.items():
        # Stack probabilities from this concept's specialists
        tr_feats = np.hstack([l0_train_probs[s] for s in specialist_names if s in l0_train_probs])
        te_feats = np.hstack([l0_test_probs[s] for s in specialist_names if s in l0_test_probs])

        model = train_model(concept_name, tr_feats, y_train, n_est=100, depth=3)
        l1_models[concept_name] = model
        l1_train_probs[concept_name] = model.predict_proba(tr_feats)
        l1_test_probs[concept_name] = model.predict_proba(te_feats)

        acc = accuracy_score(y_test, model.predict(te_feats))
        lc = l1_test_probs[concept_name][:, 5] + l1_test_probs[concept_name][:, 6]
        sc = l1_test_probs[concept_name][:, 0] + l1_test_probs[concept_name][:, 1]
        lm = lc >= 0.5; sm = sc >= 0.5
        lp = np.isin(y_test[lm], [4, 5, 6]).sum() / lm.sum() * 100 if lm.sum() > 0 else 0
        sp = np.isin(y_test[sm], [0, 1, 2]).sum() / sm.sum() * 100 if sm.sum() > 0 else 0
        specs = '+'.join(specialist_names)
        print(f"  {concept_name:20s} ({specs}) acc={acc:.2f} L@.5={lm.sum():4d}({lp:.0f}%) S@.5={sm.sum():4d}({sp:.0f}%)")

    # ═══════════════════════════════════════════════
    # LAYER 2: Final meta model
    # ═══════════════════════════════════════════════
    print(f"\n{'='*60}")
    print(f"LAYER 2: Final Meta Model")
    print(f"{'='*60}")

    X_meta_tr = np.hstack([l1_train_probs[c] for c in CONCEPTS.keys()])
    X_meta_te = np.hstack([l1_test_probs[c] for c in CONCEPTS.keys()])
    print(f"Meta features: {X_meta_tr.shape[1]} (3 concepts × 7 classes)")

    meta_model = train_model('meta', X_meta_tr, y_train, n_est=150, depth=3)
    meta_probs = meta_model.predict_proba(X_meta_te)

    deep_long, deep_short = eval_trading(meta_probs, y_test, "Deep Ensemble (3-layer)")

    # ═══════════════════════════════════════════════
    # FLAT MODEL for comparison
    # ═══════════════════════════════════════════════
    print(f"\n{'='*60}")
    print(f"FLAT MODEL (381 features, same split)")
    print(f"{'='*60}")

    X_flat_tr = train_df[feat_cols].replace([np.inf, -np.inf], np.nan).fillna(0).values
    X_flat_te = test_df[feat_cols].replace([np.inf, -np.inf], np.nan).fillna(0).values
    flat_model = train_model('flat', X_flat_tr, y_train, n_est=200, depth=5)
    flat_probs = flat_model.predict_proba(X_flat_te)

    flat_long, flat_short = eval_trading(flat_probs, y_test, "Flat Model")

    # ═══════════════════════════════════════════════
    # COMBINED SCORING: Both must agree
    # ═══════════════════════════════════════════════
    print(f"\n{'='*60}")
    print(f"COMBINED: Enter only when BOTH models agree")
    print(f"{'='*60}")

    for thr in [0.30, 0.40, 0.50, 0.60]:
        # LONG: both models say LONG above threshold
        both_long = (deep_long >= thr) & (flat_long >= thr)
        both_short = (deep_short >= thr) & (flat_short >= thr)

        if both_long.sum() > 0:
            lp = np.isin(y_test[both_long], [4, 5, 6]).sum() / both_long.sum() * 100
        else:
            lp = 0
        if both_short.sum() > 0:
            sp = np.isin(y_test[both_short], [0, 1, 2]).sum() / both_short.sum() * 100
        else:
            sp = 0

        # Also show individual counts for context
        dl = (deep_long >= thr).sum()
        fl = (flat_long >= thr).sum()
        ds = (deep_short >= thr).sum()
        fs = (flat_short >= thr).sum()

        print(f"  thr={thr:.2f}: LONG={both_long.sum():5d} ({lp:.0f}% prec) [deep={dl} flat={fl}] | "
              f"SHORT={both_short.sum():5d} ({sp:.0f}% prec) [deep={ds} flat={fs}]")

    # Concept importance in meta model
    print(f"\n=== Layer 2 — Concept Importance ===")
    imp = meta_model.feature_importances_
    for i, cname in enumerate(CONCEPTS.keys()):
        cimp = imp[i*7:(i+1)*7].sum()
        print(f"  {cname:25s}: {cimp:.4f}")

    # Save everything
    ensemble = {
        'l0_models': l0_models,
        'l1_models': l1_models,
        'meta_model': meta_model,
        'flat_model': flat_model,
        'groups': {k: v for k, v in groups.items() if v},
        'concepts': CONCEPTS,
        'group_order': list(l0_models.keys()),
        'bucket_names': BUCKET_NAMES,
    }
    path = os.path.join(args.model_dir, 'impulse_deep_ensemble.pkl')
    with open(path, 'wb') as f:
        pickle.dump(ensemble, f)
    print(f"\nDeep ensemble saved to {path}")


if __name__ == '__main__':
    from sklearn.metrics import accuracy_score
    main()
