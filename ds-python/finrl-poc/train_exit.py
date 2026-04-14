#!/usr/bin/env python3
"""
Train PPO exit optimizer on DTB combo filtered trades.

Usage:
    python train_exit.py /tmp/all_fno_combo_backtest_filtered_70.csv
    python train_exit.py /tmp/all_fno_combo_backtest_filtered_70.csv --timesteps 1000000 --tf FifteenMinute
"""
import os
os.environ["PYTHONUNBUFFERED"] = "1"  # force flush

import argparse, csv, sys, time
import numpy as np
import pandas as pd
from collections import defaultdict

sys.path.insert(0, os.path.dirname(__file__))
from data.loader import load_single
from env.exit_env import ExitOptimizerEnv

from stable_baselines3 import PPO
from stable_baselines3.common.vec_env import DummyVecEnv, SubprocVecEnv
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.callbacks import BaseCallback


class LogCallback(BaseCallback):
    def __init__(self, total_timesteps, eval_interval=10000, verbose=1):
        super().__init__(verbose)
        self.total_timesteps_target = total_timesteps
        self.eval_interval = eval_interval
        self.episode_rewards = []
        self.t0 = time.time()

    def _on_step(self):
        infos = self.locals.get("infos", [])
        for info in infos:
            if "episode" in info:
                self.episode_rewards.append(info["episode"]["r"])

        if self.num_timesteps % self.eval_interval == 0:
            pct = self.num_timesteps / self.total_timesteps_target * 100
            elapsed = time.time() - self.t0
            eta = (elapsed / max(self.num_timesteps, 1)) * (self.total_timesteps_target - self.num_timesteps)

            if self.episode_rewards:
                recent = self.episode_rewards[-100:]
                avg = np.mean(recent)
                pos = sum(1 for r in recent if r > 0)
                print(f"  [{pct:5.1f}%] Step {self.num_timesteps:>8,} | Avg reward: {avg:+.3f}% | WR: {pos}/100 | Elapsed: {elapsed:.0f}s | ETA: {eta:.0f}s", flush=True)
            else:
                print(f"  [{pct:5.1f}%] Step {self.num_timesteps:>8,} | No episodes yet | Elapsed: {elapsed:.0f}s", flush=True)
        return True


def load_trades(csv_path):
    """Load trades from filtered backtest CSV."""
    trades = []
    with open(csv_path) as f:
        for row in csv.DictReader(f):
            # Determine direction from watching_pattern or explicit column
            pat = row.get("watching_pattern", "")
            result = row.get("result", "")
            if result == "OPEN":
                continue

            # For combo CSV: bullish patterns = LONG
            is_long = pat in ("DOUBLE_BOTTOM", "TRIANGLE_ASCENDING", "HNS_BULL", "FLAG_BULL")

            try:
                entry_price = float(row["entry_price"])
                stop_loss = float(row["stop_loss"])
                target = float(row["watching_target"])
            except (ValueError, KeyError):
                continue

            if entry_price <= 0:
                continue

            trades.append({
                "symbol": row["symbol"],
                "entry_time": row["datetime"],
                "entry_price": entry_price,
                "stop_loss": stop_loss,
                "target": target,
                "direction": "LONG" if is_long else "SHORT",
                "actual_result": result,
                "actual_pnl": float(row.get("pnl_pct", 0)),
                # Entry context features for RL state
                "entry_rsi": row.get("rsi_at_p2", "50"),
                "entry_adx": row.get("adx_watching", "25"),
                "entry_macd_hist": row.get("macd_hist_at_p2", "0"),
                "entry_bb_pctb": row.get("bb_pct_b_watching", "0.5"),
                "entry_bb_width": row.get("bb_width_watching", "0.05"),
                "entry_stoch_k": row.get("stoch_rsi_k_15m", row.get("stoch_rsi_k", "0.5")),
                "entry_daily_rsi": row.get("daily_rsi", "50"),
                "rr": row.get("rr_watching", "1"),
            })
    return trades


def load_candle_cache(trades, timeframe="FifteenMinute"):
    """Load candle data for all symbols in the trades."""
    symbols = list(set(t["symbol"] for t in trades))
    print(f"Loading {timeframe} candles for {len(symbols)} symbols...")

    # Find date range
    times = [pd.Timestamp(t["entry_time"]) for t in trades]
    start = (min(times) - pd.Timedelta(days=30)).strftime("%Y-%m-%d")
    end = (max(times) + pd.Timedelta(days=30)).strftime("%Y-%m-%d")

    cache = {}
    failed = 0
    for i, sym in enumerate(symbols):
        try:
            df = load_single(sym, start, end, timeframe)
            if not df.empty:
                cache[sym] = df
        except Exception as e:
            failed += 1
        if (i + 1) % 20 == 0:
            print(f"  Loaded {i+1}/{len(symbols)} ({failed} failed)", flush=True)

    print(f"  Loaded candles for {len(cache)}/{len(symbols)} symbols ({failed} failed)")
    return cache


def evaluate(model, env, trades, n_eval=500):
    """Evaluate the trained model."""
    results = defaultdict(list)

    # Use the raw env (unwrap from DummyVecEnv + Monitor) for deterministic eval
    raw_env = env.envs[0]
    if hasattr(raw_env, 'env'):  # unwrap Monitor
        raw_env = raw_env.env

    for i in range(min(n_eval, len(trades))):
        raw_env._trade_idx = i % len(trades)
        obs, _ = raw_env.reset()
        total_reward = 0
        done = False
        bars = 0
        info = {}

        while not done:
            action, _ = model.predict(obs, deterministic=True)
            obs, reward, terminated, truncated, info = raw_env.step(action)
            total_reward += reward
            done = terminated or truncated
            bars += 1

        exit_reason = info.get("exit_reason", "UNKNOWN")
        results["pnl"].append(total_reward)
        results["exit_reason"].append(exit_reason)
        results["bars"].append(bars)
        results["actual_pnl"].append(trades[i % len(trades)]["actual_pnl"])

    pnl = np.array(results["pnl"])
    actual = np.array(results["actual_pnl"])
    wr = (pnl > 0).mean() * 100
    actual_wr = (actual > 0).mean() * 100

    # Exit reason distribution
    reasons = defaultdict(int)
    for r in results["exit_reason"]:
        reasons[r] += 1

    print(f"\n{'='*60}")
    print(f"  EVALUATION ({len(pnl)} trades)")
    print(f"{'='*60}")
    print(f"  RL Agent:    WR={wr:.1f}% | Avg PnL={pnl.mean():+.3f}% | Total={pnl.sum():+.1f}%")
    print(f"  Baseline:    WR={actual_wr:.1f}% | Avg PnL={actual.mean():+.3f}% | Total={actual.sum():+.1f}%")
    print(f"  Improvement: {pnl.mean() - actual.mean():+.3f}% per trade")
    print(f"  Avg bars held: {np.mean(results['bars']):.1f}")
    print(f"  Exit reasons: {dict(reasons)}")
    return pnl.mean()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("csv_path", help="Filtered backtest CSV")
    parser.add_argument("--timesteps", type=int, default=500_000)
    parser.add_argument("--tf", default="FifteenMinute", help="Candle timeframe for replay")
    parser.add_argument("--max-hold", type=int, default=200, help="Max bars to hold")
    parser.add_argument("--full", action="store_true", help="Train on full dataset (no holdout) for production deploy")
    args = parser.parse_args()

    # Load trades
    trades = load_trades(args.csv_path)
    print(f"Loaded {len(trades)} trades from {args.csv_path}")

    # Load candle data
    candle_cache = load_candle_cache(trades, args.tf)

    # Filter trades to those with candle data
    trades = [t for t in trades if t["symbol"] in candle_cache]
    print(f"Trades with candle data: {len(trades)}")

    if len(trades) < 100:
        print("Too few trades with candle data. Aborting.")
        return

    # Split train/eval
    np.random.seed(42)
    np.random.shuffle(trades)
    if args.full:
        train_trades = trades
        eval_trades = trades[:min(500, len(trades))]  # eval on sample for sanity check
        print(f"FULL TRAINING MODE: {len(train_trades)} trades (no holdout)", flush=True)
    else:
        split = int(len(trades) * 0.8)
        train_trades = trades[:split]
        eval_trades = trades[split:]
        print(f"Train: {len(train_trades)} | Eval: {len(eval_trades)}", flush=True)

    # Create environments with Monitor wrapper for episode tracking
    n_envs = min(4, os.cpu_count() or 1)
    print(f"Using {n_envs} parallel environments", flush=True)

    def make_env(trades_list):
        def _init():
            return Monitor(ExitOptimizerEnv(trades_list, candle_cache, max_hold_bars=args.max_hold))
        return _init

    train_env = DummyVecEnv([make_env(train_trades) for _ in range(n_envs)])
    eval_env = DummyVecEnv([make_env(eval_trades)])

    # Train PPO
    print(f"\nTraining PPO for {args.timesteps:,} timesteps...")
    model = PPO(
        "MlpPolicy",
        train_env,
        learning_rate=3e-4,
        n_steps=2048,
        batch_size=256,
        n_epochs=10,
        gamma=0.99,
        gae_lambda=0.95,
        clip_range=0.2,
        ent_coef=0.01,
        verbose=0,
    )

    callback = LogCallback(total_timesteps=args.timesteps, eval_interval=10000)
    t0 = time.time()
    model.learn(total_timesteps=args.timesteps, callback=callback)
    elapsed = time.time() - t0
    print(f"\nTraining completed in {elapsed:.0f}s")

    # Save model first (before eval, in case eval crashes)
    os.makedirs("models", exist_ok=True)
    model_path = "models/ppo_exit_optimizer"
    model.save(model_path)
    print(f"\nModel saved → {model_path}.zip", flush=True)

    # Evaluate on held-out trades
    print("\n--- Eval on TRAIN set (sample 500) ---", flush=True)
    evaluate(model, train_env, train_trades, n_eval=min(500, len(train_trades)))

    print("\n--- Eval on HELD-OUT set ---", flush=True)
    evaluate(model, eval_env, eval_trades, n_eval=len(eval_trades))


if __name__ == "__main__":
    main()
