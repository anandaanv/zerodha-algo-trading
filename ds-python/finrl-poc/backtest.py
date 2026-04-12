"""
Evaluate a trained model on the holdout eval period.
Prints a P&L summary and optionally saves trades to finrl_poc.rl_trade.

Usage:
    python backtest.py                         # RELIANCE, latest model
    python backtest.py --symbol TCS --save-db
"""
import argparse
from datetime import datetime

from stable_baselines3 import PPO
from sqlalchemy import create_engine, text

from config import TF_CONFIG, POC_DB_URL
from data.loader import load_single
from data.features import add_features
from env.trading_env import SingleStockTradingEnv


def run(symbol: str, tf: str, model_path: str, save_db: bool = False):
    cfg = TF_CONFIG[tf]
    print(f"[backtest] {symbol} {tf}  {cfg['eval_start']} → {cfg['eval_end']}")
    df_raw = load_single(symbol, cfg["eval_start"], cfg["eval_end"], cfg["timeframe"])
    df     = add_features(df_raw)
    print(f"[backtest] {len(df)} bars")

    model = PPO.load(model_path)
    env   = SingleStockTradingEnv(df)

    obs, _ = env.reset()
    done   = False
    while not done:
        action, _ = model.predict(obs, deterministic=True)
        obs, _, done, _, _ = env.step(int(action))

    summary = env.summary()
    print("\n" + "=" * 50)
    print(f"  Symbol         : {symbol}")
    print(f"  Trades         : {summary['n_trades']}")
    print(f"  Win rate       : {summary.get('win_rate', 0)}%")
    print(f"  Avg P&L/trade  : {summary.get('avg_pnl_pct', 0)}%")
    print(f"  Total return   : {summary.get('total_return', 0)}%")
    print(f"  Final capital  : ₹{summary.get('final_capital', 0):,.0f}")
    print("=" * 50)

    if save_db and env.trades:
        _save_to_db(symbol, summary, env.trades, model_path)

    return summary


def _save_to_db(symbol, summary, trades, model_path):
    engine = create_engine(POC_DB_URL)
    with engine.begin() as conn:
        res = conn.execute(text("""
            INSERT INTO rl_model (name, algorithm, symbols, timeframe, train_start, train_end, model_path)
            VALUES (:name, 'PPO', :symbols, '1h', :ts, :te, :mp)
        """), {"name": f"PPO_{symbol}", "symbols": f'["{symbol}"]',
               "ts": "2021-01-01", "te": "2024-06-30", "mp": model_path})
        model_id = res.lastrowid

        res = conn.execute(text("""
            INSERT INTO rl_episode (model_id, phase, start_date, end_date,
                total_return_pct, n_trades, win_rate)
            VALUES (:mid, 'eval', :sd, :ed, :ret, :n, :wr)
        """), {"mid": model_id, "sd": "2024-07-01", "ed": "2025-08-31",
               "ret": summary["total_return"], "n": summary["n_trades"],
               "wr": summary["win_rate"] / 100})
        episode_id = res.lastrowid

        for t in trades:
            conn.execute(text("""
                INSERT INTO rl_trade (episode_id, symbol, direction, pnl_pct)
                VALUES (:eid, :sym, :dir, :pnl)
            """), {"eid": episode_id, "sym": symbol,
                   "dir": "LONG" if t["side"] == 1 else "SHORT",
                   "pnl": round(t["pnl"] * 100, 4)})

    print(f"[backtest] Saved {len(trades)} trades to finrl_poc.rl_trade (episode {episode_id})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol",  default="RELIANCE")
    parser.add_argument("--tf",      default="Day", choices=list(TF_CONFIG.keys()))
    parser.add_argument("--model",   default=None)
    parser.add_argument("--save-db", action="store_true")
    args = parser.parse_args()

    symbol  = args.symbol
    tf_tag  = args.tf.lower().replace("minute", "m").replace("hour", "h").replace("day", "daily")
    model_path = args.model or f"models/ppo_{symbol.lower()}_{tf_tag}"
    run(symbol, args.tf, model_path, args.save_db)
