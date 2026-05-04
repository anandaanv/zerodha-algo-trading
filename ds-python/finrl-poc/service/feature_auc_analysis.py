"""
Phase-1 analytical foundation per impulse_filtering_modeling_strategy doc.

For each feature in the candidate-pivot CSVs:
  1. Single-feature AUC against the binary impulse label (sideways vs impulse).
     - AUC > 0.65 or < 0.35  → strong filter candidate
     - AUC 0.55-0.65 or 0.35-0.45 → useful for main model, weak for filter
     - AUC 0.45-0.55 → noise, drop
  2. Spearman correlation against |forward_max_move_pct| (regression target).
  3. Direction-conditional AUC (bull vs sideways, bear vs sideways).
"""
import glob, os, sys, warnings
import numpy as np, pandas as pd
from scipy.stats import spearmanr
from sklearn.metrics import roc_auc_score

warnings.filterwarnings("ignore")

DATA_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "..", "candidate_pivot_train_data")
OUT_PATH = os.path.join(os.path.dirname(__file__), "feature_auc_ranking.csv")

EXCLUDE = {
    "timestamp","price","label","symbol","timeframe","direction","candidate_status","bars_to_target",
    "forward_max_move_pct","forward_bull_pct","forward_bear_pct","bars_to_bull_max","bars_to_bear_max",
    "entry_close","pivot_price",
}


def load_data():
    pattern = os.path.join(DATA_DIR, "*_1h_candidate_pivots.csv")
    files = sorted(glob.glob(pattern))
    if not files:
        print(f"ERROR: no CSVs at {pattern}", file=sys.stderr); sys.exit(1)
    df = pd.concat([pd.read_csv(f) for f in files], ignore_index=True)
    print(f"Loaded {len(files)} files, {len(df):,} rows")
    return df


def prepare(df):
    df = df[df["label"].notna() & (df["label"] != "")].copy()
    df["direction"] = pd.to_numeric(df["direction"], errors="coerce").fillna(0).astype(int)
    df["y_bin"] = (df["label"] == "wave3_start").astype(int)
    for c in ("forward_max_move_pct","forward_bull_pct","forward_bear_pct"):
        df[c] = pd.to_numeric(df[c], errors="coerce").fillna(0)
    fcols = [c for c in df.columns if c not in EXCLUDE and c != "y_bin"]
    X = df[fcols].apply(pd.to_numeric, errors="coerce").fillna(0)
    X = X.replace([np.inf,-np.inf], np.nan).fillna(0).clip(lower=-1e6, upper=1e6)
    print(f"Features: {len(fcols)}")
    print(f"Class balance — sideways: {(df['y_bin']==0).sum():,}  impulse: {(df['y_bin']==1).sum():,}  ({df['y_bin'].mean():.3f})")
    return df, X, fcols


def per_feature_stats(df, X, fcols):
    y_bin = df["y_bin"].values
    y_abs = np.abs(df["forward_max_move_pct"].values)
    y_signed = df["forward_max_move_pct"].values
    is_sideways = df["direction"].values == 0
    is_bull = df["direction"].values == 1
    is_bear = df["direction"].values == -1
    bull_mask = is_sideways | is_bull
    bear_mask = is_sideways | is_bear
    y_bull_bin = is_bull[bull_mask].astype(int)
    y_bear_bin = is_bear[bear_mask].astype(int)
    rows = []
    for col in fcols:
        x = X[col].values
        if np.std(x) < 1e-8: continue
        try:
            auc_all = roc_auc_score(y_bin, x)
            auc_bull = roc_auc_score(y_bull_bin, x[bull_mask])
            auc_bear = roc_auc_score(y_bear_bin, x[bear_mask])
            spear_abs = spearmanr(x, y_abs).correlation
            spear_signed = spearmanr(x, y_signed).correlation
            rows.append({"feature":col, "auc":auc_all, "auc_strength":abs(auc_all-0.5),
                         "auc_bull":auc_bull, "auc_bear":auc_bear,
                         "spear_abs":spear_abs, "spear_signed":spear_signed})
        except Exception: continue
    return pd.DataFrame(rows).sort_values("auc_strength", ascending=False)


def report(res):
    res.to_csv(OUT_PATH, index=False)
    print(f"\nFull ranking → {OUT_PATH} ({len(res)} features)")
    print("\n=== Strength distribution (binary AUC) ===")
    strong = ((res["auc"]<0.35)|(res["auc"]>0.65)).sum()
    useful = (((res["auc"]>=0.55)&(res["auc"]<=0.65))|((res["auc"]>=0.35)&(res["auc"]<=0.45))).sum()
    noise = ((res["auc"]>=0.45)&(res["auc"]<=0.55)).sum()
    print(f"  Strong   (AUC<0.35 or >0.65) : {strong}")
    print(f"  Useful   (0.55-0.65 / 0.35-0.45) : {useful}")
    print(f"  Noise    (0.45-0.55)           : {noise}")
    cols = ["feature","auc","auc_bull","auc_bear","spear_abs","spear_signed"]
    fmt = lambda v: f"{v:+.3f}"
    print("\n=== Top 25 by binary AUC distance from 0.5 ===")
    print(res[cols].head(25).to_string(index=False, float_format=fmt))
    res2 = res.copy(); res2["spear_abs_mag"] = res2["spear_abs"].abs()
    print("\n=== Top 25 by |Spearman vs |forward_move|| ===")
    print(res2.sort_values("spear_abs_mag", ascending=False)[cols].head(25).to_string(index=False, float_format=fmt))
    res3 = res.copy(); res3["bull_strength"] = (res3["auc_bull"]-0.5).abs()
    print("\n=== Top 15 BULL-discriminating features ===")
    print(res3.sort_values("bull_strength", ascending=False)[cols].head(15).to_string(index=False, float_format=fmt))
    res4 = res.copy(); res4["bear_strength"] = (res4["auc_bear"]-0.5).abs()
    print("\n=== Top 15 BEAR-discriminating features ===")
    print(res4.sort_values("bear_strength", ascending=False)[cols].head(15).to_string(index=False, float_format=fmt))


def main():
    df = load_data()
    df, X, fcols = prepare(df)
    res = per_feature_stats(df, X, fcols)
    report(res)


if __name__ == "__main__":
    main()
