"""
RL Entry Timing Environment.

Given a detected pattern (XGBoost says it's a good setup), the agent decides
WHEN to enter — WAIT (0) or ENTER (1) — within an entry window.

This is for options trading where entry timing matters:
- Enter too early = pay more premium, risk SL hit before move starts
- Enter too late = miss the move
- Enter at the right moment = optimal premium and probability

Observation (31 features — same as exit env for consistency):
  Entry context (from pattern detection):
                 rsi, adx, macd_hist, bb_pctb, bb_width, stoch_k, daily_rsi, rr, is_long
  Market state:  price_vs_entry (how far from planned entry level),
                 bars_in_window_norm, time_remaining_norm,
                 distance_to_sl, distance_to_target
  Live indicators:
                 rsi, stoch_rsi_k, macd_hist, macd_cross, adx, adx_momentum,
                 bb_pct_b, bb_width, rsi_slope, macd_hist_slope,
                 price_vs_ema, atr_ratio
  Candle:        body_atr_ratio, upper_wick_ratio, lower_wick_ratio, volume_ratio

Action: 0 = WAIT, 1 = ENTER NOW

Reward:
  - On ENTER: simulate trade forward using the exit env logic, return net PnL
  - On WAIT (each bar): small theta penalty (time decay)
  - On window expiry without entering: 0 reward (missed opportunity)
"""
import numpy as np
import gymnasium as gym
from gymnasium import spaces
import pandas as pd


class EntryTimingEnv(gym.Env):
    metadata = {"render_modes": []}

    N_FEATURES = 31
    OVERHEAD_PCT = 0.001
    THETA_PER_BAR = 0.015  # penalty for waiting

    def __init__(self, trades: list[dict], candle_cache: dict,
                 entry_window_bars: int = 20, max_hold_bars: int = 100,
                 theta_decay: float = 0.015):
        """
        Args:
            trades: list of trade dicts with entry_time, entry_price, sl, target, etc.
            candle_cache: dict[symbol] -> DataFrame of candles
            entry_window_bars: how many bars the agent has to decide to enter
            max_hold_bars: max bars to hold after entry (for PnL simulation)
            theta_decay: penalty per bar for waiting/holding
        """
        super().__init__()
        self.trades = trades
        self.candle_cache = candle_cache
        self.entry_window_bars = entry_window_bars
        self.max_hold_bars = max_hold_bars
        self.theta_decay = theta_decay

        self.observation_space = spaces.Box(
            low=-np.inf, high=np.inf,
            shape=(self.N_FEATURES,), dtype=np.float32
        )
        self.action_space = spaces.Discrete(2)  # 0=WAIT, 1=ENTER

        self._trade_idx = 0
        self._bar_idx = 0
        self._bars = None
        self._entry_price_planned = 0.0
        self._sl = 0.0
        self._target = 0.0
        self._is_long = True
        self._entry_context = np.zeros(9, dtype=np.float32)

    def reset(self, *, seed=None, options=None):
        super().reset(seed=seed)

        self._trade_idx = self.np_random.integers(0, len(self.trades))
        trade = self.trades[self._trade_idx]

        self._entry_price_planned = trade["entry_price"]
        self._is_long = trade["direction"] == "LONG"
        self._sl = trade["stop_loss"]
        self._target = trade["target"]

        symbol = trade["symbol"]
        entry_time = pd.Timestamp(trade["entry_time"]).tz_localize(None)

        df = self.candle_cache.get(symbol)
        if df is None or df.empty:
            self._bars = np.zeros((1, 5))
            self._bar_idx = 0
            return np.zeros(self.N_FEATURES, dtype=np.float32), {}

        # Start a few bars BEFORE entry time so agent can observe the approach
        idx = df.index.searchsorted(entry_time)
        start = max(0, idx - 5)  # 5 bars before planned entry
        end = min(len(df), idx + self.entry_window_bars + self.max_hold_bars)
        episode_df = df.iloc[start:end]

        self._bars = episode_df[["open", "high", "low", "close", "volume"]].values
        self._bar_idx = 0
        self._entry_bar_offset = idx - start  # bar index where planned entry was

        def _s(key, default=0.0):
            v = trade.get(key, default)
            try:
                return float(v) if v not in (None, "", "null") else default
            except (ValueError, TypeError):
                return default

        self._entry_context = np.array([
            _s("entry_rsi", 50) / 100.0,
            _s("entry_adx", 25) / 100.0,
            _s("entry_macd_hist", 0),
            _s("entry_bb_pctb", 0.5),
            _s("entry_bb_width", 0.05),
            _s("entry_stoch_k", 0.5),
            _s("entry_daily_rsi", 50) / 100.0,
            min(_s("rr", 1.0), 10.0) / 10.0,
            1.0 if self._is_long else 0.0,
        ], dtype=np.float32)

        return self._get_obs(), {}

    def step(self, action):
        self._bar_idx += 1
        terminated = False
        truncated = False

        # Check if entry window expired
        bars_in_window = self._bar_idx - self._entry_bar_offset
        if bars_in_window >= self.entry_window_bars or self._bar_idx >= len(self._bars):
            # Missed the entry window
            terminated = True
            return self._get_obs(), 0.0, terminated, truncated, {"entry_reason": "WINDOW_EXPIRED"}

        bar = self._bars[self._bar_idx]
        close = bar[3]

        if action == 1:  # ENTER NOW
            # Simulate the trade forward from this bar
            actual_entry = close
            reward = self._simulate_trade(actual_entry, self._bar_idx)
            terminated = True
            return self._get_obs(), reward, terminated, truncated, {
                "entry_reason": "AGENT_ENTERED",
                "entry_bar": self._bar_idx,
                "bars_waited": bars_in_window
            }

        # WAIT — theta penalty
        return self._get_obs(), -self.theta_decay, terminated, truncated, {}

    def _simulate_trade(self, entry_price, entry_bar_idx):
        """Simulate trade forward from entry, return PnL minus theta."""
        sl = self._sl
        target = self._target
        is_long = self._is_long
        bars_held = 0

        for k in range(entry_bar_idx + 1, min(len(self._bars), entry_bar_idx + self.max_hold_bars)):
            bar = self._bars[k]
            high, low, close = bar[1], bar[2], bar[3]
            bars_held += 1

            # SL check
            if is_long and low <= sl:
                exit_price = sl
                pnl = (exit_price - entry_price) / entry_price - self.OVERHEAD_PCT
                theta = bars_held * self.theta_decay / 100.0
                return (pnl - theta) * 100.0
            if not is_long and high >= sl:
                exit_price = sl
                pnl = (entry_price - exit_price) / entry_price - self.OVERHEAD_PCT
                theta = bars_held * self.theta_decay / 100.0
                return (pnl - theta) * 100.0

            # Target check
            if is_long and high >= target:
                pnl = (target - entry_price) / entry_price - self.OVERHEAD_PCT
                theta = bars_held * self.theta_decay / 100.0
                return (pnl - theta) * 100.0
            if not is_long and low <= target:
                pnl = (entry_price - target) / entry_price - self.OVERHEAD_PCT
                theta = bars_held * self.theta_decay / 100.0
                return (pnl - theta) * 100.0

        # Max hold — exit at last close
        if bars_held > 0 and entry_bar_idx + bars_held < len(self._bars):
            last_close = self._bars[entry_bar_idx + bars_held, 3]
            if is_long:
                pnl = (last_close - entry_price) / entry_price - self.OVERHEAD_PCT
            else:
                pnl = (entry_price - last_close) / entry_price - self.OVERHEAD_PCT
            theta = bars_held * self.theta_decay / 100.0
            return (pnl - theta) * 100.0
        return 0.0

    def _get_obs(self):
        if self._bar_idx >= len(self._bars):
            return np.zeros(self.N_FEATURES, dtype=np.float32)

        bar = self._bars[self._bar_idx]
        o, h, l, c, v = bar

        entry = self._entry_price_planned
        sl = self._sl
        target = self._target

        # Price vs planned entry
        price_vs_entry = (c - entry) / entry if entry > 0 else 0
        bars_in_window = max(0, self._bar_idx - self._entry_bar_offset)
        window_norm = bars_in_window / self.entry_window_bars
        remaining_norm = 1.0 - window_norm

        if self._is_long:
            dist_to_target = (target - c) / entry if entry > 0 else 0
            dist_to_sl = (c - sl) / entry if entry > 0 else 0
        else:
            dist_to_target = (c - target) / entry if entry > 0 else 0
            dist_to_sl = (sl - c) / entry if entry > 0 else 0

        # Simplified live features (candle-derived)
        candle_range = max(h - l, 0.01)
        body = abs(c - o)

        live_obs = np.array([
            price_vs_entry, 0, 0, window_norm, dist_to_target, dist_to_sl,
            0.5, 0.5, 0, 0, 0.25, 0,  # placeholder indicators
            0.5, 0.05, 0, 0,
            0, 1.0,
            body / candle_range, (h - max(o, c)) / candle_range,
            (min(o, c) - l) / candle_range, 1.0,
        ], dtype=np.float32)

        return np.concatenate([self._entry_context, live_obs])
