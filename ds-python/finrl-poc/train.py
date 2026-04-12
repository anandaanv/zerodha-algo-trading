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

from config import TRAIN_START, TRAIN_END, TIMESTEPS
from data.loader import load_single
from data.features import add_features
from env.trading_env import SingleStockTradingEnv


def train(symbol: str, timesteps: int = TIMESTEPS) -> str:
    print(f"[train] Loading {symbol} {TRAIN_START} → {TRAIN_END} ...")
    df_raw = load_single(symbol, TRAIN_START, TRAIN_END)
    df     = add_features(df_raw)
    print(f"[train] {len(df)} bars after feature computation")

    env = SingleStockTradingEnv(df)
    check_env(env, warn=True)

    model = PPO(
        "MlpPolicy", env,
        learning_rate=3e-4,
        n_steps=2048,
        batch_size=64,
        n_epochs=10,
        gamma=0.99,
        verbose=1,
    )

    print(f"[train] Training PPO for {timesteps:,} timesteps ...")
    model.learn(total_timesteps=timesteps, progress_bar=True)

    os.makedirs("models", exist_ok=True)
    path = f"models/ppo_{symbol.lower()}_1h"
    model.save(path)
    print(f"[train] Model saved → {path}.zip")
    return path


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default="RELIANCE")
    parser.add_argument("--steps",  type=int, default=TIMESTEPS)
    args = parser.parse_args()
    train(args.symbol, args.steps)
