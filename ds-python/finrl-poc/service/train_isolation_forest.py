"""
Anomaly-detection filter per impulse_filtering_modeling_strategy doc §4.2 Tier 1.

Train IsolationForest on confirmed clean-impulse rows ONLY. Score new candidates
by anomaly distance from the impulse manifold. Reject candidates that don't match.

Goal (doc §3.2): reject 70-80% of candidates while retaining ≥85% of true impulses.

Pipeline test:
  Stage A — XGBoost (clean_impulse_xgb_1h.pkl) at thr 0.30 — base bull/bear precision
  Stage B — IsolationForest filter — must pass anomaly threshold to enter
  Combined: precision at each anomaly threshold
"""
import glob, time, warnings
import numpy as np, pandas as pd, joblib
from sklearn.ensemble import IsolationForest

warnings.filterwarnings("ignore")

DATA_DIR = "/codes/algotrade/zerodha-algo-trading/clean_impulse_train_data"
XGB_PATH = "/codes/algotrade/zerodha-algo-trading/ds-python/finrl-poc/service/model/clean_impulse_xgb_1h.pkl"
OUT_PATH = "/codes/algotrade/zerodha-algo-trading/ds-python/finrl-poc/service/model/iforest_clean_impulse.pkl"

EXCLUDE = {
    "timestamp","price","label","symbol","timeframe","direction","y","candidate_status","bars_to_target",
    "forward_max_move_pct","forward_bull_pct","forward_bear_pct","bars_to_bull_max","bars_to_bear_max",
    "entry_close","pivot_price","ts_dt",
}


def load_data():
    files = sorted(glob.glob(f"{DATA_DIR}/*_1h_clean_impulse.csv"))
    df = pd.concat([pd.read_csv(f) for f in files], ignore_index=True)
    df = df[df["label"].notna() & (df["label"] != "")].copy()
    df["direction"] = pd.to_numeric(df["direction"], errors="coerce").fillna(0).astype(int)
    df["y"] = 0
    mask = df["label"] == "wave3_start"
    df.loc[mask & (df["direction"]==1), "y"] = 1
    df.loc[mask & (df["direction"]==-1), "y"] = 2
    df["ts_dt"] = pd.to_datetime(df["timestamp"])
    df = df.sort_values("ts_dt").reset_index(drop=True)
    print(f"Loaded {len(files)} files, {len(df):,} rows")
    print(f"Class: sideways={ (df['y']==0).sum():,}  bull={(df['y']==1).sum():,}  bear={(df['y']==2).sum():,}")
    return df


def main():
    df = load_data()
    fcols = [c for c in df.columns if c not in EXCLUDE]
    print(f"Features: {len(fcols)}")

    # Numerical X
    X_all = df[fcols].apply(pd.to_numeric, errors="coerce").fillna(0)
    X_all = X_all.replace([np.inf,-np.inf], np.nan).fillna(0).clip(-1e6, 1e6).values.astype(np.float32)

    # 80/20 chrono split
    split = int(len(df) * 0.8)
    train_df = df.iloc[:split]; test_df = df.iloc[split:]
    X_tr = X_all[:split]; X_te = X_all[split:]

    # Train IsolationForest on POSITIVE class only (clean-impulse rows in train slice)
    pos_mask_tr = train_df["y"] > 0
    X_pos = X_tr[pos_mask_tr.values]
    print(f"\nIsolationForest train: {len(X_pos):,} clean-impulse rows  ({train_df['ts_dt'].min().date()} → {train_df['ts_dt'].max().date()})")

    t0 = time.time()
    iso = IsolationForest(
        contamination=0.05,    # expect ~5% outliers in the impulse training set
        n_estimators=200,
        max_samples=512,
        random_state=42,
        n_jobs=-1,
    )
    iso.fit(X_pos)
    print(f"Trained {time.time()-t0:.1f}s")

    # Score test set
    score_te = iso.decision_function(X_te)  # higher = more "normal" (impulse-like)
    test_df = test_df.copy()
    test_df["iso_score"] = score_te

    # Score distribution by class
    print(f"\n=== Anomaly score (decision_function) by class on TEST ===")
    for cls, name in [(0,'sideways'), (1,'bull'), (2,'bear')]:
        vals = test_df.loc[test_df["y"]==cls, "iso_score"].values
        if len(vals) == 0: continue
        print(f"  {name:9s} n={len(vals):,}  median={np.median(vals):+.4f}  p25={np.quantile(vals,0.25):+.4f}  p75={np.quantile(vals,0.75):+.4f}  min={vals.min():+.4f}")

    # Threshold sweep — for each iso threshold, compute retention/rejection
    print(f"\n=== IsolationForest threshold sweep on TEST ===")
    print(f"{'thr':>8s}  {'kept_n':>7s}  {'kept%':>7s}  {'imp_ret%':>9s}  {'sw_rej%':>8s}  {'imp_in_kept%':>13s}")
    for thr in (-0.10, -0.05, -0.02, 0.00, 0.02, 0.05, 0.08, 0.10, 0.12):
        keep = test_df["iso_score"] >= thr
        n_keep = keep.sum()
        n_imp_total = (test_df["y"]>0).sum()
        n_sw_total = (test_df["y"]==0).sum()
        imp_kept = ((test_df["y"]>0) & keep).sum()
        sw_kept = ((test_df["y"]==0) & keep).sum()
        imp_ret = imp_kept / max(1, n_imp_total)
        sw_rej = 1 - sw_kept / max(1, n_sw_total)
        imp_pct_in_kept = imp_kept / max(1, n_keep)
        print(f"{thr:+8.3f}  {n_keep:7d}  {n_keep/len(test_df):7.1%}  {imp_ret:9.1%}  {sw_rej:8.1%}  {imp_pct_in_kept:13.1%}")

    # Combine with XGBoost — does iso filter improve XGBoost precision?
    print(f"\n=== Combined: XGBoost (thr 0.30) + IsolationForest filter ===")
    xgb = joblib.load(XGB_PATH)
    proba = xgb.predict_proba(X_te)
    test_df["p_bull"] = proba[:,1]
    test_df["p_bear"] = proba[:,2]

    for iso_thr in (-0.05, 0.00, 0.05, 0.10):
        for xgb_thr in (0.30, 0.40, 0.50):
            df_pass_iso = test_df[test_df["iso_score"] >= iso_thr]
            for side, p_col, y_val in [('bull','p_bull',1), ('bear','p_bear',2)]:
                bull_pred = df_pass_iso[df_pass_iso[p_col] >= xgb_thr]
                if len(bull_pred) == 0: continue
                tp = (bull_pred["y"] == y_val).sum()
                prec = tp / len(bull_pred)
                # Baseline (no iso filter)
                baseline = test_df[test_df[p_col] >= xgb_thr]
                bp_tp = (baseline["y"] == y_val).sum()
                bp_prec = bp_tp / max(1, len(baseline))
                lift = (prec - bp_prec) / max(0.0001, bp_prec) * 100
                print(f"  iso_thr {iso_thr:+.2f} xgb_thr {xgb_thr:.2f} {side}: "
                      f"n={len(bull_pred):4d} prec={prec:.3f}  vs baseline n={len(baseline):4d} prec={bp_prec:.3f}  "
                      f"lift={lift:+.1f}%")

    # Save
    joblib.dump(iso, OUT_PATH)
    print(f"\nSaved → {OUT_PATH}")


if __name__ == "__main__":
    main()
