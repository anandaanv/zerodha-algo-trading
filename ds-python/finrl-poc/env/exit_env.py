"""
RL Exit Optimizer Environment.

Given a trade entry (from XGBoost-filtered backtest), the agent observes
market state each bar and decides HOLD (0) or EXIT (1).

Observation (31 features):
  Entry context (frozen, from backtest CSV):
                 entry_rsi, entry_adx, entry_macd_hist, entry_bb_pctb, entry_bb_width,
                 entry_stoch_k, entry_daily_rsi, entry_rr, is_long
  Trade vitals:  unrealized_pnl, mfe, mae, bars_held_norm, dist_to_target, dist_to_sl
  Live indicators:
                 rsi, stoch_rsi_k, macd_hist, macd_signal_cross, adx, adx_momentum,
                 bb_pct_b, bb_width, rsi_slope, macd_hist_slope,
                 price_vs_ema_atr, atr_ratio
  Candle:        body_atr_ratio, upper_wick_ratio, lower_wick_ratio, volume_ratio

Action: 0 = HOLD, 1 = EXIT

Reward: realized PnL % on exit (minus overhead), 0 while holding.
"""
import numpy as np
import gymnasium as gym
from gymnasium import spaces
import pandas as pd


class ExitOptimizerEnv(gym.Env):
    metadata = {"render_modes": []}

    N_FEATURES = 31  # 9 entry context + 6 trade vitals + 12 live indicators + 4 candle
    OVERHEAD_PCT = 0.001  # 0.1% transaction cost
    THETA_FACTOR = 0.05   # theta_per_bar = (ATR / entry_price) * THETA_FACTOR
                          # e.g., ATR=20, price=1000 → 2% * 0.05 = 0.1% per bar

    def __init__(self, trades: list[dict], candle_cache: dict,
                 max_hold_bars: int = 200, ema_period: int = 200,
                 theta_factor: float = 0.05):
        """
        Args:
            trades: list of dicts with keys: symbol, entry_time, entry_price,
                    stop_loss, target, direction ('LONG'/'SHORT')
            candle_cache: dict[symbol] -> DataFrame with columns:
                          datetime, open, high, low, close, volume
                          indexed by datetime, sorted ascending
            max_hold_bars: force exit after this many bars
            ema_period: EMA period for price-vs-EMA feature
        """
        super().__init__()
        self.trades = trades
        self.candle_cache = candle_cache
        self.max_hold_bars = max_hold_bars
        self.ema_period = ema_period
        self.theta_factor = theta_factor  # theta = ATR/price * this factor

        self.observation_space = spaces.Box(
            low=-np.inf, high=np.inf,
            shape=(self.N_FEATURES,), dtype=np.float32
        )
        self.action_space = spaces.Discrete(2)  # 0=HOLD, 1=EXIT

        # Episode state
        self._trade_idx = 0
        self._bar_idx = 0
        self._entry_price = 0.0
        self._is_long = True
        self._sl = 0.0
        self._target = 0.0
        self._bars = None       # numpy array of OHLCV for current trade
        self._mfe = 0.0
        self._mae = 0.0
        self._peak = 0.0
        self._theta_per_bar = 0.001  # default, overridden per trade in reset()
        self._entry_context = np.zeros(9, dtype=np.float32)

        # Precomputed indicators per symbol
        self._indicator_cache = {}

    def reset(self, *, seed=None, options=None):
        super().reset(seed=seed)

        # Pick a random trade
        self._trade_idx = self.np_random.integers(0, len(self.trades))
        trade = self.trades[self._trade_idx]

        self._entry_price = trade["entry_price"]
        self._is_long = trade["direction"] == "LONG"
        self._sl = trade["stop_loss"]
        self._target = trade["target"]

        # Compute ATR-linked theta: theta_per_bar = (SL distance / entry_price) * theta_factor
        # Using SL distance as ATR proxy since ATR ≈ |entry - SL| / 2
        sl_dist = abs(self._entry_price - self._sl)
        atr_pct = sl_dist / self._entry_price if self._entry_price > 0 else 0.01
        self._theta_per_bar = atr_pct * self.theta_factor  # e.g., 2% * 0.05 = 0.1% per bar

        symbol = trade["symbol"]
        entry_time = pd.Timestamp(trade["entry_time"]).tz_localize(None)

        # Get candle data for this symbol
        df = self.candle_cache.get(symbol)
        if df is None or df.empty:
            # Fallback: return zeros and terminate
            self._bars = np.zeros((1, 5))
            self._bar_idx = 0
            return np.zeros(self.N_FEATURES, dtype=np.float32), {}

        # Find entry bar index
        idx = df.index.searchsorted(entry_time)
        if idx >= len(df):
            idx = len(df) - 1

        # Slice from entry to entry + max_hold_bars
        end_idx = min(idx + self.max_hold_bars + 1, len(df))
        episode_df = df.iloc[idx:end_idx]

        self._bars = episode_df[["open", "high", "low", "close", "volume"]].values
        self._bar_idx = 0
        self._mfe = 0.0
        self._mae = 0.0
        self._peak = self._entry_price

        # Entry context — frozen features from the backtest CSV (same every bar)
        def _s(key, default=0.0):
            v = trade.get(key, default)
            try:
                return float(v) if v not in (None, "", "null") else default
            except (ValueError, TypeError):
                return default

        rr = _s("rr", 1.0)
        self._entry_context = np.array([
            _s("entry_rsi", 50) / 100.0,
            _s("entry_adx", 25) / 100.0,
            _s("entry_macd_hist", 0),
            _s("entry_bb_pctb", 0.5),
            _s("entry_bb_width", 0.05),
            _s("entry_stoch_k", 0.5),
            _s("entry_daily_rsi", 50) / 100.0,
            min(rr, 10.0) / 10.0,  # normalize R:R to 0-1
            1.0 if self._is_long else 0.0,
        ], dtype=np.float32)

        # Precompute indicators for this slice
        self._ep_indicators = self._compute_indicators(symbol, df, idx, end_idx)

        obs = self._get_obs()
        return obs, {}

    def step(self, action):
        self._bar_idx += 1
        terminated = False
        truncated = False
        reward = 0.0

        if self._bar_idx >= len(self._bars):
            # Ran out of bars — force exit at last close
            close = self._bars[-1, 3]
            reward = self._calc_pnl(close)
            terminated = True
            return self._get_obs(), reward, terminated, truncated, {"exit_reason": "MAX_BARS"}

        bar = self._bars[self._bar_idx]  # open, high, low, close, volume
        high, low, close = bar[1], bar[2], bar[3]

        # Update MFE/MAE
        if self._is_long:
            self._peak = max(self._peak, high)
            fav = (high - self._entry_price) / self._entry_price
            adv = max(0, (self._entry_price - low) / self._entry_price)
        else:
            self._peak = min(self._peak, low)
            fav = (self._entry_price - low) / self._entry_price
            adv = max(0, (high - self._entry_price) / self._entry_price)
        self._mfe = max(self._mfe, fav)
        self._mae = max(self._mae, adv)

        # Check SL hit (forced exit regardless of action)
        sl_hit = False
        if self._is_long and low <= self._sl:
            sl_hit = True
            exit_price = self._sl
        elif not self._is_long and high >= self._sl:
            sl_hit = True
            exit_price = self._sl

        if sl_hit:
            reward = self._calc_pnl(exit_price)
            terminated = True
            return self._get_obs(), reward, terminated, truncated, {"exit_reason": "SL_HIT"}

        # Agent decision
        if action == 1:  # EXIT
            reward = self._calc_pnl(close)
            terminated = True
            return self._get_obs(), reward, terminated, truncated, {"exit_reason": "AGENT_EXIT"}

        # HOLD — negative reward for time decay (options theta, ATR-linked)
        obs = self._get_obs()
        theta_penalty = -self._theta_per_bar * 100.0  # convert to pct points
        return obs, theta_penalty, terminated, truncated, {}

    def _calc_pnl(self, exit_price):
        if self._is_long:
            pnl = (exit_price - self._entry_price) / self._entry_price - self.OVERHEAD_PCT
        else:
            pnl = (self._entry_price - exit_price) / self._entry_price - self.OVERHEAD_PCT
        # Subtract accumulated theta for bars held (ATR-linked)
        theta_cost = self._bar_idx * self._theta_per_bar
        return (pnl - theta_cost) * 100.0  # return as percentage

    def _get_obs(self):
        if self._bar_idx >= len(self._bars):
            return np.zeros(self.N_FEATURES, dtype=np.float32)

        bar = self._bars[self._bar_idx]
        o, h, l, c, v = bar

        entry = self._entry_price
        sl = self._sl
        target = self._target

        # Trade vitals
        if self._is_long:
            unrealized_pnl = (c - entry) / entry
            dist_to_target = (target - c) / entry if target > 0 else 0
            dist_to_sl = (c - sl) / entry if sl > 0 else 0
        else:
            unrealized_pnl = (entry - c) / entry
            dist_to_target = (c - target) / entry if target > 0 else 0
            dist_to_sl = (sl - c) / entry if sl > 0 else 0

        bars_norm = self._bar_idx / self.max_hold_bars

        # Indicators (precomputed)
        ind = self._ep_indicators
        idx = min(self._bar_idx, len(ind["rsi"]) - 1)

        rsi = ind["rsi"][idx] / 100.0 if idx < len(ind["rsi"]) else 0.5
        stoch_k = ind["stoch_k"][idx] if idx < len(ind["stoch_k"]) else 0.5
        macd_hist = ind["macd_hist"][idx] if idx < len(ind["macd_hist"]) else 0
        macd_cross = ind["macd_cross"][idx] if idx < len(ind["macd_cross"]) else 0
        adx = ind["adx"][idx] / 100.0 if idx < len(ind["adx"]) else 0.25
        adx_mom = ind["adx_mom"][idx] / 100.0 if idx < len(ind["adx_mom"]) else 0
        bb_pctb = ind["bb_pctb"][idx] if idx < len(ind["bb_pctb"]) else 0.5
        bb_width = ind["bb_width"][idx] if idx < len(ind["bb_width"]) else 0.05
        rsi_slope = ind["rsi_slope"][idx] if idx < len(ind["rsi_slope"]) else 0
        macd_slope = ind["macd_slope"][idx] if idx < len(ind["macd_slope"]) else 0
        price_vs_ema = ind["price_vs_ema"][idx] if idx < len(ind["price_vs_ema"]) else 0
        atr_ratio = ind["atr_ratio"][idx] if idx < len(ind["atr_ratio"]) else 1.0

        # Candle features
        body = abs(c - o)
        atr_val = ind["atr_raw"][idx] if idx < len(ind["atr_raw"]) else max(h - l, 0.01)
        body_atr = body / max(atr_val, 0.01)
        candle_range = max(h - l, 0.01)
        upper_wick = (h - max(o, c)) / candle_range
        lower_wick = (min(o, c) - l) / candle_range
        vol_ratio = v / max(ind["vol_avg"][idx], 1) if idx < len(ind["vol_avg"]) else 1.0

        live_obs = np.array([
            unrealized_pnl, self._mfe, self._mae, bars_norm, dist_to_target, dist_to_sl,
            rsi, stoch_k, macd_hist, macd_cross, adx, adx_mom,
            bb_pctb, bb_width, rsi_slope, macd_slope,
            price_vs_ema, atr_ratio,
            body_atr, upper_wick, lower_wick, vol_ratio,
        ], dtype=np.float32)

        # Prepend frozen entry context (9 features) to live observation (22 features)
        return np.concatenate([self._entry_context, live_obs])

    def _compute_indicators(self, symbol, full_df, start_idx, end_idx):
        """Precompute indicators for the episode slice. Uses a lookback window."""
        lookback = max(self.ema_period + 50, 300)
        lb_start = max(0, start_idx - lookback)
        df = full_df.iloc[lb_start:end_idx].copy()

        close = df["close"].values
        high = df["high"].values
        low = df["low"].values
        volume = df["volume"].values
        n = len(close)

        # ATR (14)
        atr = np.zeros(n)
        for i in range(1, n):
            tr = max(high[i] - low[i], abs(high[i] - close[i-1]), abs(low[i] - close[i-1]))
            atr[i] = atr[i-1] * 13/14 + tr/14 if i > 1 else tr
        atr[0] = high[0] - low[0]

        # EMA (200)
        ema = np.zeros(n)
        mult = 2 / (self.ema_period + 1)
        ema[0] = close[0]
        for i in range(1, n):
            ema[i] = close[i] * mult + ema[i-1] * (1 - mult)

        # RSI (14)
        rsi = np.full(n, 50.0)
        gains = np.zeros(n)
        losses = np.zeros(n)
        for i in range(1, n):
            delta = close[i] - close[i-1]
            if delta > 0: gains[i] = delta
            else: losses[i] = -delta
        avg_gain = avg_loss = 0.0
        for i in range(1, min(15, n)):
            avg_gain += gains[i]
            avg_loss += losses[i]
        avg_gain /= 14; avg_loss /= 14
        if avg_loss > 0: rsi[14] = 100 - 100/(1+avg_gain/avg_loss)
        for i in range(15, n):
            avg_gain = (avg_gain * 13 + gains[i]) / 14
            avg_loss = (avg_loss * 13 + losses[i]) / 14
            if avg_loss > 0: rsi[i] = 100 - 100/(1+avg_gain/avg_loss)

        # Stoch RSI K (14, 3-smooth)
        stoch_k = np.full(n, 0.5)
        for i in range(14, n):
            rsi_window = rsi[i-13:i+1]
            rsi_min, rsi_max = rsi_window.min(), rsi_window.max()
            stoch_k[i] = (rsi[i] - rsi_min) / max(rsi_max - rsi_min, 0.01)

        # MACD (12, 26, 9)
        ema12 = np.zeros(n); ema26 = np.zeros(n)
        ema12[0] = ema26[0] = close[0]
        for i in range(1, n):
            ema12[i] = close[i] * 2/13 + ema12[i-1] * 11/13
            ema26[i] = close[i] * 2/27 + ema26[i-1] * 25/27
        macd_line = ema12 - ema26
        macd_signal = np.zeros(n)
        macd_signal[0] = macd_line[0]
        for i in range(1, n):
            macd_signal[i] = macd_line[i] * 2/10 + macd_signal[i-1] * 8/10
        macd_hist = macd_line - macd_signal

        # Normalize MACD hist by ATR
        macd_hist_norm = macd_hist / np.maximum(atr, 0.01)

        # MACD cross (1 if MACD > signal, -1 if below)
        macd_cross = np.sign(macd_line - macd_signal)

        # ADX (14) — simplified
        adx = np.full(n, 25.0)
        plus_dm = np.zeros(n)
        minus_dm = np.zeros(n)
        for i in range(1, n):
            up = high[i] - high[i-1]
            down = low[i-1] - low[i]
            plus_dm[i] = up if up > down and up > 0 else 0
            minus_dm[i] = down if down > up and down > 0 else 0
        sm_plus = sm_minus = sm_tr = 0.0
        for i in range(1, min(15, n)):
            tr = max(high[i]-low[i], abs(high[i]-close[i-1]), abs(low[i]-close[i-1]))
            sm_tr += tr; sm_plus += plus_dm[i]; sm_minus += minus_dm[i]
        for i in range(15, n):
            tr = max(high[i]-low[i], abs(high[i]-close[i-1]), abs(low[i]-close[i-1]))
            sm_tr = sm_tr - sm_tr/14 + tr
            sm_plus = sm_plus - sm_plus/14 + plus_dm[i]
            sm_minus = sm_minus - sm_minus/14 + minus_dm[i]
            di_plus = 100 * sm_plus / max(sm_tr, 0.01)
            di_minus = 100 * sm_minus / max(sm_tr, 0.01)
            dx = 100 * abs(di_plus - di_minus) / max(di_plus + di_minus, 0.01)
            adx[i] = (adx[i-1] * 13 + dx) / 14

        # ADX momentum (ADX - 9-EMA of ADX)
        adx_ema = np.zeros(n)
        adx_ema[0] = adx[0]
        for i in range(1, n):
            adx_ema[i] = adx[i] * 2/10 + adx_ema[i-1] * 8/10
        adx_mom = adx - adx_ema

        # Bollinger Bands (20, 2)
        bb_pctb = np.full(n, 0.5)
        bb_width = np.full(n, 0.05)
        for i in range(20, n):
            window = close[i-19:i+1]
            mid = window.mean()
            std = window.std()
            upper = mid + 2 * std
            lower = mid - 2 * std
            bb_pctb[i] = (close[i] - lower) / max(upper - lower, 0.01)
            bb_width[i] = (upper - lower) / max(mid, 0.01)

        # Slopes (5-bar linear regression)
        rsi_slope = np.zeros(n)
        macd_slope = np.zeros(n)
        for i in range(5, n):
            x = np.arange(5, dtype=np.float64)
            rsi_slope[i] = np.polyfit(x, rsi[i-4:i+1], 1)[0]
            macd_slope[i] = np.polyfit(x, macd_hist_norm[i-4:i+1], 1)[0]

        # Price vs EMA in ATR units
        price_vs_ema = (close - ema) / np.maximum(atr, 0.01)

        # ATR ratio (current / at entry)
        atr_entry = atr[start_idx - lb_start] if (start_idx - lb_start) < n else 1.0
        atr_ratio = atr / max(atr_entry, 0.01)

        # Volume 20-bar moving avg
        vol_avg = np.ones(n)
        for i in range(20, n):
            vol_avg[i] = volume[i-19:i+1].mean()

        # Slice to episode range
        ep_start = start_idx - lb_start
        ep_end = end_idx - lb_start

        return {
            "rsi": rsi[ep_start:ep_end],
            "stoch_k": stoch_k[ep_start:ep_end],
            "macd_hist": macd_hist_norm[ep_start:ep_end],
            "macd_cross": macd_cross[ep_start:ep_end],
            "adx": adx[ep_start:ep_end],
            "adx_mom": adx_mom[ep_start:ep_end],
            "bb_pctb": bb_pctb[ep_start:ep_end],
            "bb_width": bb_width[ep_start:ep_end],
            "rsi_slope": rsi_slope[ep_start:ep_end],
            "macd_slope": macd_slope[ep_start:ep_end],
            "price_vs_ema": price_vs_ema[ep_start:ep_end],
            "atr_ratio": atr_ratio[ep_start:ep_end],
            "atr_raw": atr[ep_start:ep_end],
            "vol_avg": vol_avg[ep_start:ep_end],
        }
