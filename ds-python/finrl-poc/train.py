"""
Train a PPO agent on a single symbol.

Usage:
    python train.py                        # RELIANCE, default config
    python train.py --symbol TCS --steps 200000
"""
import argparse
import os
import sys

from stable_baselines3 import PPO
from stable_baselines3.common.env_checker import check_env

from config import TF_CONFIG, TIMESTEPS
from data.loader import load_single
from data.features import add_features
from env.trading_env import SingleStockTradingEnv


def train(symbol: str, tf: str = "Day", timesteps: int = None) -> str:
    cfg = TF_CONFIG[tf]
    steps = timesteps or cfg["timesteps"]
    print(f"[train] Loading {symbol} {tf}  {cfg['train_start']} → {cfg['train_end']} ...")
    df_raw = load_single(symbol, cfg["train_start"], cfg["train_end"], cfg["timeframe"])
    df     = add_features(df_raw)
    print(f"[train] {len(df)} bars after feature computation")

    env = SingleStockTradingEnv(
        df,
        window_size=cfg.get("window_size"),
        max_hold_bars=cfg.get("max_hold_bars"),
        flat_penalty=cfg.get("flat_penalty", 0.0),
    )
    check_env(env, warn=True)

    model = PPO(
        "MlpPolicy", env,
        learning_rate=3e-4,
        n_steps=min(2048, cfg.get("window_size", len(df)) - 1),
        batch_size=64,
        n_epochs=10,
        gamma=0.99,
        verbose=1,
    )

    print(f"[train] Training PPO for {steps:,} timesteps ...")
    model.learn(total_timesteps=steps, progress_bar=True)

    os.makedirs("models", exist_ok=True)
    tf_tag = tf.lower().replace("minute", "m").replace("hour", "h").replace("day", "daily")
    path = f"models/ppo_{symbol.lower()}_{tf_tag}"
    model.save(path)
    print(f"[train] Model saved → {path}.zip")
    return path


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default="RELIANCE")
    parser.add_argument("--tf",     default="Day", choices=list(TF_CONFIG.keys()))
    parser.add_argument("--steps",  type=int, default=None)
    args = parser.parse_args()
    train(args.symbol, args.tf, args.steps)
