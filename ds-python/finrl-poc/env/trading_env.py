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

    def __init__(
        self,
        df,
        initial_capital: float = INITIAL_CAPITAL,
        window_size: int | None = None,   # bars per episode; None = full dataset
        max_hold_bars: int | None = None, # force-close after N bars; None = unlimited
        flat_penalty: float = 0.0,        # reward penalty per bar when flat (no position)
    ):
        super().__init__()
        self.df_full       = df.reset_index(drop=True)
        self.initial_capital = initial_capital
        self.window_size   = window_size
        self.max_hold_bars = max_hold_bars
        self.flat_penalty  = flat_penalty
        self.n_features    = 6
        self.feature_cols  = ["close_pct", "rsi", "atr", "macd_hist", "bb_pct", "vol_ratio"]

        self._close_full = self.df_full["close"].values
        self._feats_full = self.df_full[self.feature_cols].values.astype(np.float32)

        # obs = feature_cols + [position_flag, bars_in_position_norm]
        self.observation_space = spaces.Box(
            low=-np.inf, high=np.inf,
            shape=(self.n_features + 2,), dtype=np.float32
        )
        # 0 = hold, 1 = go long, 2 = go short
        self.action_space = spaces.Discrete(3)

        # working arrays — set in reset()
        self._close = self._close_full
        self._feats = self._feats_full
        self._ep_len = len(self.df_full)

        self.reset()

    # ──────────────────────────────────────────────────────────────────────
    def reset(self, *, seed=None, options=None):
        super().reset(seed=seed)

        # Random window slice for each episode
        n = len(self.df_full)
        if self.window_size and self.window_size < n:
            start = self.np_random.integers(0, n - self.window_size)
            end   = start + self.window_size
            self._close  = self._close_full[start:end]
            self._feats  = self._feats_full[start:end]
            self._ep_len = self.window_size
        else:
            self._close  = self._close_full
            self._feats  = self._feats_full
            self._ep_len = n

        self.step_idx     = 0
        self.position     = 0       # +1 long, -1 short, 0 flat
        self.entry_price  = 0.0
        self.bars_held    = 0
        self.capital      = self.initial_capital
        self.trades       = []
        return self._obs(), {}

    def step(self, action):
        price   = float(self._close[self.step_idx])
        reward  = 0.0
        info    = {}

        # ── Force-close if max_hold_bars exceeded ────────────────────────
        if self.position != 0 and self.max_hold_bars and self.bars_held >= self.max_hold_bars:
            action = 2 if self.position == 1 else 1   # flip to trigger close

        # ── Close existing position ──────────────────────────────────────
        if self.position != 0:
            should_close = (
                (self.position == 1  and action == 2) or
                (self.position == -1 and action == 1) or
                (self.step_idx >= self._ep_len - 2)
            )
            if should_close:
                pnl_pct = self.position * (price - self.entry_price) / self.entry_price
                pnl_pct -= TRANSACTION_COST_PCT
                reward  += pnl_pct * 100
                self.capital *= (1 + pnl_pct)
                self.trades.append({
                    "entry": self.entry_price,
                    "exit":  price,
                    "pnl":   pnl_pct,
                    "side":  self.position,
                })
                self.position  = 0
                self.bars_held = 0

        # ── Open new position ────────────────────────────────────────────
        if self.position == 0 and action in (1, 2):
            self.position    = 1 if action == 1 else -1
            self.entry_price = price * (1 + TRANSACTION_COST_PCT * self.position)
            self.bars_held   = 0

        # ── Per-step rewards ─────────────────────────────────────────────
        self.step_idx += 1
        done      = self.step_idx >= self._ep_len - 1
        truncated = False

        if self.position != 0 and not done:
            # Mark-to-market: continuous signal on open position
            next_price = float(self._close[self.step_idx])
            mtm = self.position * (next_price - price) / price * 100
            reward += mtm
            self.bars_held += 1
        elif self.position == 0:
            # Flat penalty: nudge agent away from pure inaction
            reward -= self.flat_penalty

        return self._obs(), reward, done, truncated, info

    # ──────────────────────────────────────────────────────────────────────
    def _obs(self):
        feats = self._feats[self.step_idx].copy()
        close = self._close[self.step_idx]
        if close > 0:
            feats[2] /= close   # atr → atr/close
            feats[3] /= close   # macd_hist → macd_hist/close
        pos_flag  = np.float32(self.position)
        hold_norm = np.float32(self.bars_held / (self.max_hold_bars or 100))
        return np.append(feats, [pos_flag, hold_norm])

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
