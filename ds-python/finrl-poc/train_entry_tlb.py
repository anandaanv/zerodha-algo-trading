#!/usr/bin/env python3
"""Train PPO entry timing agent on TLB backtest trades."""
import os
os.environ["PYTHONUNBUFFERED"] = "1"

import argparse, csv, sys, time
import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from data.loader import load_single
from env.entry_env import EntryTimingEnv
from stable_baselines3 import PPO
from stable_baselines3.common.vec_env import DummyVecEnv
from stable_baselines3.common.monitor import Monitor


def load_trades(csv_path):
    trades = []
    with open(csv_path) as f:
        for row in csv.DictReader(f):
            result = row.get("result", "")
            if result == "OPEN": continue
            try:
                entry_price = float(row["entry_price"])
                stop_loss = float(row["sl_trendline"])
                target = float(row["target"])
            except (ValueError, KeyError): continue
            if entry_price <= 0: continue
            trades.append({
                "symbol": row["symbol"],
                "entry_time": row["datetime"],
                "entry_price": entry_price,
                "stop_loss": stop_loss,
                "target": target,
                "direction": row.get("direction", "LONG"),
                "actual_result": result,
                "actual_pnl": float(row.get("pnl_pct", 0)),
                "entry_rsi": row.get("rsi_at_p2", "50"),
                "entry_adx": row.get("adx_watching", "25"),
                "entry_macd_hist": row.get("macd_hist_at_p2", "0"),
                "entry_bb_pctb": row.get("bb_pct_b_watching", "0.5"),
                "entry_bb_width": row.get("bb_width_watching", "0.05"),
                "entry_stoch_k": row.get("stoch_rsi_k", "0.5"),
                "entry_daily_rsi": row.get("daily_rsi", "50"),
                "rr": row.get("rr", "1"),
            })
    return trades


def load_candle_cache(trades, timeframe="FifteenMinute"):
    symbols = list(set(t["symbol"] for t in trades))
    print(f"Loading {timeframe} candles for {len(symbols)} symbols...", flush=True)
    times = [pd.Timestamp(t["entry_time"]) for t in trades]
    start = (min(times) - pd.Timedelta(days=30)).strftime("%Y-%m-%d")
    end = (max(times) + pd.Timedelta(days=30)).strftime("%Y-%m-%d")
    cache = {}; failed = 0
    for i, sym in enumerate(symbols):
        try:
            df = load_single(sym, start, end, timeframe)
            if not df.empty: cache[sym] = df
        except: failed += 1
        if (i + 1) % 20 == 0: print(f"  {i+1}/{len(symbols)} ({failed} failed)", flush=True)
    print(f"  Done: {len(cache)}/{len(symbols)}", flush=True)
    return cache


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("csv_path")
    parser.add_argument("--timesteps", type=int, default=300000)
    parser.add_argument("--tf", default="FifteenMinute")
    args = parser.parse_args()

    trades = load_trades(args.csv_path)
    print(f"Loaded {len(trades)} trades", flush=True)
    candle_cache = load_candle_cache(trades, args.tf)
    trades = [t for t in trades if t["symbol"] in candle_cache]
    print(f"With data: {len(trades)}", flush=True)

    np.random.seed(42); np.random.shuffle(trades)
    split = int(len(trades) * 0.8)
    train_trades, eval_trades = trades[:split], trades[split:]
    print(f"Train: {len(train_trades)} | Eval: {len(eval_trades)}", flush=True)

    def make_env(t):
        def _init(): return Monitor(EntryTimingEnv(t, candle_cache, entry_window_bars=20, max_hold_bars=100))
        return _init

    train_env = DummyVecEnv([make_env(train_trades) for _ in range(4)])
    model = PPO("MlpPolicy", train_env, learning_rate=3e-4, n_steps=2048,
                batch_size=256, n_epochs=10, gamma=0.99, ent_coef=0.02, verbose=0)

    t0 = time.time()
    for step in range(0, args.timesteps, 10000):
        model.learn(total_timesteps=10000, reset_num_timesteps=False)
        pct = (step + 10000) / args.timesteps * 100
        elapsed = time.time() - t0
        print(f"  [{pct:5.1f}%] Step {step+10000:>8,} | Elapsed: {elapsed:.0f}s", flush=True)

    print(f"\nDone in {time.time()-t0:.0f}s", flush=True)
    os.makedirs("models", exist_ok=True)
    model.save("models/ppo_entry_timer")
    print("Saved → models/ppo_entry_timer.zip", flush=True)

    # Eval
    raw_env = EntryTimingEnv(eval_trades, candle_cache, entry_window_bars=20, max_hold_bars=100)
    pnls, entered, bars_waited = [], 0, []
    for i in range(min(300, len(eval_trades))):
        raw_env._trade_idx = i % len(eval_trades)
        obs, _ = raw_env.reset(); done = False; total_r = 0
        while not done:
            action, _ = model.predict(obs, deterministic=True)
            obs, r, terminated, truncated, info = raw_env.step(action)
            total_r += r; done = terminated or truncated
        pnls.append(total_r)
        if info.get("entry_reason") == "AGENT_ENTERED":
            entered += 1; bars_waited.append(info.get("bars_waited", 0))

    pnl = np.array(pnls)
    wr = (pnl > 0).mean() * 100
    baseline = np.mean([t["actual_pnl"] for t in eval_trades[:len(pnl)]])
    print(f"\n{'='*60}", flush=True)
    print(f"  ENTRY TIMING EVAL (OOS, {len(pnl)} trades)", flush=True)
    print(f"  Entered: {entered} | Skipped: {len(pnl)-entered}", flush=True)
    print(f"  WR: {wr:.1f}% | Avg PnL: {pnl.mean():+.3f}%", flush=True)
    print(f"  Avg bars waited: {np.mean(bars_waited):.1f}" if bars_waited else "  No entries", flush=True)
    print(f"  Baseline PnL: {baseline:+.3f}%", flush=True)

if __name__ == "__main__":
    main()
