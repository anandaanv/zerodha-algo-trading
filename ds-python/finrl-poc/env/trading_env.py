"""
Single-symbol discrete trading gym environment.

Observation: [close_pct, rsi, atr_norm, macd_hist_norm, bb_pct, vol_ratio, position]
Action:       0 = hold, 1 = buy/long, 2 = sell/short
"""
import numpy as np
import gymnasium as gym
from gymnasium import spaces
from config import INITIAL_CAPITAL, TRANSACTION_COST_PCT


class SingleStockTradingEnv(gym.Env):
    metadata = {"render_modes": []}

    def __init__(self, df, initial_capital: float = INITIAL_CAPITAL):
        super().__init__()
        self.df            = df.reset_index(drop=True)
        self.initial_capital = initial_capital
        self.n_features    = 6  # close_pct, rsi, atr, macd_hist, bb_pct, vol_ratio
        self.feature_cols  = ["close_pct", "rsi", "atr", "macd_hist", "bb_pct", "vol_ratio"]

        # Normalise ATR and MACD by close price at each step
        self._close = self.df["close"].values
        self._feats = self.df[self.feature_cols].values.astype(np.float32)

        # obs = feature_cols + [position_flag]
        self.observation_space = spaces.Box(
            low=-np.inf, high=np.inf,
            shape=(self.n_features + 1,), dtype=np.float32
        )
        # 0 = hold, 1 = go long, 2 = go short
        self.action_space = spaces.Discrete(3)

        self.reset()

    # ──────────────────────────────────────────────────────────────────────
    def reset(self, *, seed=None, options=None):
        super().reset(seed=seed)
        self.step_idx     = 0
        self.position     = 0       # +1 long, -1 short, 0 flat
        self.entry_price  = 0.0
        self.capital      = self.initial_capital
        self.trades       = []
        return self._obs(), {}

    def step(self, action):
        price   = float(self._close[self.step_idx])
        reward  = 0.0
        info    = {}

        # ── Close existing position ──────────────────────────────────────
        if self.position != 0:
            # Close if action says go opposite or hold and end-of-data
            should_close = (
                (self.position == 1  and action == 2) or
                (self.position == -1 and action == 1) or
                (self.step_idx == len(self.df) - 2)
            )
            if should_close:
                pnl_pct = self.position * (price - self.entry_price) / self.entry_price
                pnl_pct -= TRANSACTION_COST_PCT
                reward  = pnl_pct * 100          # scale reward to ~0–5 range
                self.capital *= (1 + pnl_pct)
                self.trades.append({
                    "entry": self.entry_price,
                    "exit":  price,
                    "pnl":   pnl_pct,
                    "side":  self.position,
                })
                self.position = 0

        # ── Open new position ────────────────────────────────────────────
        if self.position == 0 and action in (1, 2):
            self.position    = 1 if action == 1 else -1
            self.entry_price = price * (1 + TRANSACTION_COST_PCT * self.position)

        self.step_idx += 1
        done      = self.step_idx >= len(self.df) - 1
        truncated = False

        return self._obs(), reward, done, truncated, info

    # ──────────────────────────────────────────────────────────────────────
    def _obs(self):
        feats = self._feats[self.step_idx].copy()
        # Normalise ATR and MACD_hist by current close so they're scale-free
        close = self._close[self.step_idx]
        if close > 0:
            feats[2] /= close   # atr → atr/close
            feats[3] /= close   # macd_hist → macd_hist/close
        pos_flag = np.float32(self.position)
        return np.append(feats, pos_flag)

    def summary(self) -> dict:
        if not self.trades:
            return {"n_trades": 0, "total_return": 0.0, "win_rate": 0.0}
        pnls    = [t["pnl"] for t in self.trades]
        wins    = sum(1 for p in pnls if p > 0)
        total_r = (self.capital / self.initial_capital - 1) * 100
        return {
            "n_trades":     len(self.trades),
            "total_return": round(total_r, 2),
            "win_rate":     round(wins / len(self.trades) * 100, 1),
            "avg_pnl_pct":  round(sum(pnls) / len(pnls) * 100, 3),
            "final_capital": round(self.capital, 0),
        }
