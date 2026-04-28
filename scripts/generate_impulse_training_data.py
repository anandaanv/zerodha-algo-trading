"""
Generate impulse training data directly from MySQL candle data.
No Java dependency, no ZigZag pivot matching.

For every bar:
  - Compute indicators (RSI, MACD, ADX, Stoch, BB, EMAs)
  - Label: did price move >=7% in next 19 bars? (bullish/bearish/none)
  - Output: CSV with features + label

Usage:
    python scripts/generate_impulse_training_data.py --symbols RELIANCE,HDFCBANK
    python scripts/generate_impulse_training_data.py --all-fno
    python scripts/generate_impulse_training_data.py --symbols RELIANCE --min-move 5.0 --lookahead 15
"""
import argparse
import os
import sys
import numpy as np
import pandas as pd
import pymysql
from datetime import datetime

# ─── Indicator computation ───

def compute_ema(series, period):
    return series.ewm(span=period, adjust=False).mean()

def compute_rsi(close, period=14):
    delta = close.diff()
    gain = delta.where(delta > 0, 0.0)
    loss = -delta.where(delta < 0, 0.0)
    avg_gain = gain.ewm(alpha=1/period, min_periods=period).mean()
    avg_loss = loss.ewm(alpha=1/period, min_periods=period).mean()
    rs = avg_gain / avg_loss.replace(0, np.nan)
    return 100 - (100 / (1 + rs))

def compute_macd(close, fast=12, slow=26, signal=9):
    ema_fast = compute_ema(close, fast)
    ema_slow = compute_ema(close, slow)
    macd_line = ema_fast - ema_slow
    signal_line = compute_ema(macd_line, signal)
    histogram = macd_line - signal_line
    return macd_line, signal_line, histogram

def compute_stochastic(high, low, close, k_period=14, d_period=3):
    lowest_low = low.rolling(k_period).min()
    highest_high = high.rolling(k_period).max()
    k = 100 * (close - lowest_low) / (highest_high - lowest_low).replace(0, np.nan)
    d = k.rolling(d_period).mean()
    return k, d

def compute_adx(high, low, close, period=14):
    tr1 = high - low
    tr2 = abs(high - close.shift(1))
    tr3 = abs(low - close.shift(1))
    tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
    atr = tr.ewm(alpha=1/period, min_periods=period).mean()
    
    up_move = high - high.shift(1)
    down_move = low.shift(1) - low
    
    plus_dm = np.where((up_move > down_move) & (up_move > 0), up_move, 0.0)
    minus_dm = np.where((down_move > up_move) & (down_move > 0), down_move, 0.0)
    
    plus_dm = pd.Series(plus_dm, index=high.index).ewm(alpha=1/period, min_periods=period).mean()
    minus_dm = pd.Series(minus_dm, index=high.index).ewm(alpha=1/period, min_periods=period).mean()
    
    plus_di = 100 * plus_dm / atr.replace(0, np.nan)
    minus_di = 100 * minus_dm / atr.replace(0, np.nan)
    
    dx = 100 * abs(plus_di - minus_di) / (plus_di + minus_di).replace(0, np.nan)
    adx = dx.ewm(alpha=1/period, min_periods=period).mean()
    
    return adx, plus_di, minus_di, atr

def compute_bollinger(close, period=20, std_dev=2.0):
    sma = close.rolling(period).mean()
    std = close.rolling(period).std()
    upper = sma + std_dev * std
    lower = sma - std_dev * std
    bandwidth = (upper - lower) / sma.replace(0, np.nan)
    position = (close - lower) / (upper - lower).replace(0, np.nan)
    return upper, sma, lower, bandwidth, position


def compute_all_indicators(df):
    """Compute all indicators and return as new columns."""
    c = df['close']
    h = df['high']
    l = df['low']
    v = df['volume']
    
    # EMAs
    df['ema20'] = compute_ema(c, 20)
    df['ema50'] = compute_ema(c, 50)
    df['ema200'] = compute_ema(c, 200)
    df['ema20_dist'] = (c - df['ema20']) / df['ema20'] * 100
    df['ema50_dist'] = (c - df['ema50']) / df['ema50'] * 100
    df['ema200_dist'] = (c - df['ema200']) / df['ema200'] * 100
    
    # RSI
    df['rsi'] = compute_rsi(c)
    
    # MACD
    df['macd_line'], df['macd_signal'], df['macd_hist'] = compute_macd(c)
    
    # Stochastic
    df['stoch_k'], df['stoch_d'] = compute_stochastic(h, l, c)
    
    # ADX
    df['adx'], df['plus_di'], df['minus_di'], df['atr'] = compute_adx(h, l, c)
    
    # Bollinger Bands
    df['bb_upper'], df['bb_mid'], df['bb_lower'], df['bb_bandwidth'], df['bb_position'] = compute_bollinger(c)
    
    # BBW EMAs (for trend detection)
    df['bbw_ema10'] = compute_ema(df['bb_bandwidth'], 10)
    df['bbw_ema50'] = compute_ema(df['bb_bandwidth'], 50)
    df['bbw_ema100'] = compute_ema(df['bb_bandwidth'], 100)
    
    # Volume
    df['vol_sma20'] = v.rolling(20).mean()
    df['vol_ratio'] = v / df['vol_sma20'].replace(0, np.nan)
    
    # Candle patterns
    body = abs(c - df['open'])
    full_range = (h - l).replace(0, np.nan)
    df['body_pct'] = body / full_range
    df['upper_wick'] = (h - pd.concat([c, df['open']], axis=1).max(axis=1)) / full_range
    df['lower_wick'] = (pd.concat([c, df['open']], axis=1).min(axis=1) - l) / full_range
    
    # Bar direction and streaks
    df['bar_dir'] = np.where(c > df['open'], 1, np.where(c < df['open'], -1, 0))
    df['dir_3bar'] = df['bar_dir'].rolling(3).sum()
    
    # Range expansion
    df['range_exp'] = (h - l) / df['atr'].replace(0, np.nan)
    
    # Price change
    df['pct_change_1'] = c.pct_change() * 100
    df['pct_change_5'] = c.pct_change(5) * 100
    df['pct_change_20'] = c.pct_change(20) * 100
    
    # High/Low distance (how far from recent extremes)
    df['dist_from_20h'] = (c - h.rolling(20).max()) / c * 100
    df['dist_from_20l'] = (c - l.rolling(20).min()) / c * 100
    df['dist_from_50h'] = (c - h.rolling(50).max()) / c * 100
    df['dist_from_50l'] = (c - l.rolling(50).min()) / c * 100
    
    return df


def label_impulses(df, atr_mult=4.0, lookahead=19):
    """Label each bar using ATR-based threshold: 0=no_impulse, 1=bullish, 2=bearish."""
    n = len(df)
    labels = np.zeros(n, dtype=int)
    bars_to_target = np.full(n, -1, dtype=int)
    move_size = np.zeros(n)
    
    closes = df['close'].values
    highs = df['high'].values
    lows = df['low'].values
    atrs = df['atr'].values  # pre-computed ATR
    
    for i in range(n - lookahead):
        start_close = closes[i]
        if start_close <= 0 or atrs[i] <= 0:
            continue
        
        threshold = atr_mult * atrs[i]
        
        max_high = start_close
        min_low = start_close
        max_high_idx = i
        min_low_idx = i
        
        for j in range(i + 1, min(i + lookahead + 1, n)):
            if highs[j] > max_high:
                max_high = highs[j]
                max_high_idx = j
            if lows[j] < min_low:
                min_low = lows[j]
                min_low_idx = j
        
        bull_move = max_high - start_close
        bear_move = start_close - min_low
        
        if bull_move >= threshold and bull_move >= bear_move:
            labels[i] = 1
            bars_to_target[i] = max_high_idx - i
            move_size[i] = bull_move / start_close * 100
        elif bear_move >= threshold:
            labels[i] = 2
            bars_to_target[i] = min_low_idx - i
            move_size[i] = bear_move / start_close * 100
    
    df['label'] = labels
    df['bars_to_target'] = bars_to_target
    df['move_size'] = move_size
    df['threshold_pct'] = atr_mult * atrs / np.where(closes > 0, closes, 1) * 100
    return df


def dedup_labels(df, gap=5):
    """Remove duplicate labels within `gap` bars of each other. Keep the first."""
    labels = df['label'].values.copy()
    last_labeled = -gap - 1
    for i in range(len(labels)):
        if labels[i] > 0:
            if i - last_labeled < gap:
                labels[i] = 0
                df.iloc[i, df.columns.get_loc('bars_to_target')] = -1
                df.iloc[i, df.columns.get_loc('move_size')] = 0
            else:
                last_labeled = i
    df['label'] = labels
    return df


def compute_zigzag_pivots(df, atr_mult=2.0, hysteresis=1.6, atr_len=14):
    """Simple ZigZag pivot detection. Returns list of (index, type, price)."""
    highs = df['high'].values
    lows = df['low'].values
    closes = df['close'].values
    
    # Compute ATR
    trs = np.zeros(len(df))
    trs[0] = highs[0] - lows[0]
    for i in range(1, len(df)):
        trs[i] = max(highs[i] - lows[i], abs(highs[i] - closes[i-1]), abs(lows[i] - closes[i-1]))
    
    atr = np.zeros(len(df))
    atr[0] = trs[0]
    for i in range(1, len(df)):
        if i < atr_len:
            atr[i] = np.mean(trs[:i+1])
        else:
            atr[i] = (atr[i-1] * (atr_len - 1) + trs[i]) / atr_len
    
    direction = None
    extreme_price = None
    extreme_idx = 0
    pivots = []
    
    for i in range(len(df)):
        threshold = atr_mult * atr[i] * hysteresis
        if direction is None:
            direction = 'UP'
            extreme_price = highs[i]
            extreme_idx = i
        elif direction == 'UP':
            if highs[i] > extreme_price:
                extreme_price = highs[i]
                extreme_idx = i
            if extreme_price - lows[i] >= threshold:
                pivots.append((extreme_idx, 'HIGH', extreme_price))
                direction = 'DOWN'
                extreme_price = lows[i]
                extreme_idx = i
        elif direction == 'DOWN':
            if lows[i] < extreme_price:
                extreme_price = lows[i]
                extreme_idx = i
            if highs[i] - extreme_price >= threshold:
                pivots.append((extreme_idx, 'LOW', extreme_price))
                direction = 'UP'
                extreme_price = highs[i]
                extreme_idx = i
    
    return pivots


def add_pivot_features(df, pivots):
    """For each bar, add indicator values at the nearest prior pivot."""
    pivot_cols = ['rsi', 'adx', 'macd_hist', 'stoch_k', 'bb_position', 'bb_bandwidth',
                  'ema20_dist', 'ema50_dist', 'plus_di', 'minus_di', 'range_exp', 'vol_ratio']
    
    # Initialize pivot feature columns
    for col in pivot_cols:
        df[f'pivot_{col}'] = np.nan
    df['pivot_distance'] = np.nan
    df['pivot_type'] = 0  # 1=HIGH, -1=LOW
    df['pivot_price_dist_pct'] = np.nan
    
    if not pivots:
        return df
    
    # For each bar, find nearest prior pivot
    pivot_idx = 0
    for i in range(len(df)):
        # Advance pivot pointer
        while pivot_idx < len(pivots) - 1 and pivots[pivot_idx + 1][0] <= i:
            pivot_idx += 1
        
        if pivots[pivot_idx][0] > i:
            continue  # No prior pivot yet
        
        p_idx, p_type, p_price = pivots[pivot_idx]
        df.iloc[i, df.columns.get_loc('pivot_distance')] = i - p_idx
        df.iloc[i, df.columns.get_loc('pivot_type')] = 1 if p_type == 'HIGH' else -1
        
        close_now = df.iloc[i]['close']
        if close_now > 0:
            df.iloc[i, df.columns.get_loc('pivot_price_dist_pct')] = (close_now - p_price) / close_now * 100
        
        # Copy indicator values from pivot bar
        for col in pivot_cols:
            if col in df.columns and p_idx < len(df):
                df.iloc[i, df.columns.get_loc(f'pivot_{col}')] = df.iloc[p_idx][col]
    
    return df


def add_htf_features(cursor, df, symbol, htf='Day'):
    """Add higher-timeframe indicators mapped to each primary-TF bar."""
    cursor.execute("""
        SELECT c.timestamp, c.open, c.high, c.low, c.close, c.volume
        FROM candle c 
        JOIN instrument i ON c.instrument_instrument_token = i.instrument_token
        WHERE i.tradingsymbol = %s AND i.exchange = 'NSE'
          AND c.timeframe = %s
        ORDER BY c.timestamp
    """, (symbol, htf))
    rows = cursor.fetchall()
    
    if len(rows) < 50:
        return df  # Not enough HTF data
    
    htf_df = pd.DataFrame(rows, columns=['timestamp', 'open', 'high', 'low', 'close', 'volume'])
    htf_df['timestamp'] = pd.to_datetime(htf_df['timestamp'])
    htf_df = compute_all_indicators(htf_df)
    
    # Map HTF indicators to primary TF bars (each primary bar gets the latest HTF bar's values)
    htf_cols = ['rsi', 'adx', 'macd_hist', 'stoch_k', 'stoch_d', 'bb_position', 'bb_bandwidth',
                'bbw_ema10', 'bbw_ema50', 'bbw_ema100', 'ema20_dist', 'ema50_dist',
                'plus_di', 'minus_di', 'pct_change_1', 'pct_change_5', 'vol_ratio']
    
    # Build HTF lookup: for each date, get the indicator values
    htf_df['date'] = htf_df['timestamp'].dt.date
    htf_by_date = htf_df.set_index('date')
    
    df['date'] = df['timestamp'].dt.date
    
    for col in htf_cols:
        if col in htf_by_date.columns:
            # Map: for each primary bar's date, lookup the HTF value
            mapped = df['date'].map(htf_by_date[col].to_dict())
            df[f'htf_{col}'] = mapped
    
    df.drop(columns=['date'], inplace=True)
    
    return df




def add_ltf_features(cursor, df, symbol, ltf='FifteenMinute'):
    """Add lower-timeframe indicators mapped to each primary-TF bar."""
    cursor.execute("""
        SELECT c.timestamp, c.open, c.high, c.low, c.close, c.volume
        FROM candle c 
        JOIN instrument i ON c.instrument_instrument_token = i.instrument_token
        WHERE i.tradingsymbol = %s AND i.exchange = 'NSE'
          AND c.timeframe = %s
        ORDER BY c.timestamp
    """, (symbol, ltf))
    rows = cursor.fetchall()
    
    if len(rows) < 200:
        return df
    
    ltf_df = pd.DataFrame(rows, columns=['timestamp', 'open', 'high', 'low', 'close', 'volume'])
    ltf_df['timestamp'] = pd.to_datetime(ltf_df['timestamp'])
    ltf_df = compute_all_indicators(ltf_df)
    
    ltf_cols = ['rsi', 'adx', 'macd_hist', 'stoch_k', 'bb_position', 'bb_bandwidth',
                'bbw_ema10', 'bbw_ema50', 'bbw_ema100', 'ema20_dist', 'plus_di', 'minus_di',
                'vol_ratio', 'range_exp']
    
    # For each primary bar, find the latest LTF bar at or before that timestamp
    ltf_df = ltf_df.sort_values('timestamp')
    
    # Build a mapping: for each primary bar timestamp, find closest LTF bar
    ltf_timestamps = ltf_df['timestamp'].values
    primary_timestamps = df['timestamp'].values
    
    # Use searchsorted for efficient lookup
    ltf_indices = np.searchsorted(ltf_timestamps, primary_timestamps, side='right') - 1
    ltf_indices = np.clip(ltf_indices, 0, len(ltf_df) - 1)
    
    for col in ltf_cols:
        if col in ltf_df.columns:
            df[f'ltf_{col}'] = ltf_df[col].values[ltf_indices]
    
    return df


def process_symbol(cursor, symbol, tf='OneHour', atr_mult=4.0, lookahead=19):
    """Process one symbol: load data, compute indicators, add HTF + pivot features, label."""
    cursor.execute("""
        SELECT c.timestamp, c.open, c.high, c.low, c.close, c.volume
        FROM candle c 
        JOIN instrument i ON c.instrument_instrument_token = i.instrument_token
        WHERE i.tradingsymbol = %s AND i.exchange = 'NSE'
          AND c.timeframe = %s
        ORDER BY c.timestamp
    """, (symbol, tf))
    rows = cursor.fetchall()
    
    if len(rows) < 250:
        return None, f"Insufficient data for {symbol}: {len(rows)} bars"
    
    df = pd.DataFrame(rows, columns=['timestamp', 'open', 'high', 'low', 'close', 'volume'])
    df['timestamp'] = pd.to_datetime(df['timestamp'])
    df['symbol'] = symbol
    
    # Compute primary TF indicators
    df = compute_all_indicators(df)
    
    # Compute ZigZag pivots and add pivot-based features
    pivots = compute_zigzag_pivots(df)
    df = add_pivot_features(df, pivots)
    
    # Add HTF (daily) indicators
    df = add_htf_features(cursor, df, symbol, htf='Day')
    
    # Add LTF (15-min) indicators
    df = add_ltf_features(cursor, df, symbol, ltf='FifteenMinute')
    
    # Label impulses
    df = label_impulses(df, atr_mult=atr_mult, lookahead=lookahead)
    
    # Dedup (5-bar gap)
    df = dedup_labels(df, gap=5)
    
    # Drop warmup rows (first 200 bars for indicator stabilization)
    df = df.iloc[200:].reset_index(drop=True)
    
    bull = (df['label'] == 1).sum()
    bear = (df['label'] == 2).sum()
    
    return df, f"{symbol}: {len(df)} bars, {bull} bullish, {bear} bearish impulses"


def main():
    parser = argparse.ArgumentParser(description="Generate impulse training data from MySQL")
    parser.add_argument('--symbols', default=None, help='Comma-separated symbols')
    parser.add_argument('--all-fno', action='store_true', help='Use all FnO symbols from DB')
    parser.add_argument('--tf', default='OneHour', help='Timeframe (default: OneHour)')
    parser.add_argument('--atr-mult', type=float, default=4.0, help='ATR multiplier for impulse threshold')
    parser.add_argument('--lookahead', type=int, default=19, help='Lookahead bars for label')
    parser.add_argument('--output', default='elliott_train_data/impulse_training_v2.csv', help='Output CSV')
    parser.add_argument('--db-host', default='localhost')
    parser.add_argument('--db-user', default='anand')
    parser.add_argument('--db-pass', default='password')
    parser.add_argument('--db-name', default='algotrading')
    args = parser.parse_args()
    
    conn = pymysql.connect(host=args.db_host, user=args.db_user, password=args.db_pass, database=args.db_name)
    cursor = conn.cursor()
    
    if args.all_fno:
        cursor.execute("""
            SELECT DISTINCT i.tradingsymbol FROM instrument i
            JOIN candle c ON c.instrument_instrument_token = i.instrument_token
            WHERE i.exchange = 'NSE' AND i.instrument_type = 'EQ' AND c.timeframe = %s
            GROUP BY i.tradingsymbol HAVING COUNT(*) > 500
            ORDER BY i.tradingsymbol
        """, (args.tf,))
        symbols = [r[0] for r in cursor.fetchall()]
        print(f"Found {len(symbols)} symbols with sufficient data")
    elif args.symbols:
        symbols = [s.strip() for s in args.symbols.split(',')]
    else:
        symbols = ['RELIANCE']
    
    all_dfs = []
    for i, symbol in enumerate(symbols):
        df, msg = process_symbol(cursor, symbol, args.tf, args.atr_mult, args.lookahead)
        if df is not None:
            all_dfs.append(df)
        print(f"  [{i+1}/{len(symbols)}] {msg}")
    
    if not all_dfs:
        print("No data generated!")
        sys.exit(1)
    
    combined = pd.concat(all_dfs, ignore_index=True)
    
    # Summary
    total = len(combined)
    bull = (combined['label'] == 1).sum()
    bear = (combined['label'] == 2).sum()
    none = (combined['label'] == 0).sum()
    
    print(f"\n=== SUMMARY ===")
    print(f"Total rows: {total:,}")
    print(f"Bullish impulses: {bull:,} ({bull/total*100:.1f}%)")
    print(f"Bearish impulses: {bear:,} ({bear/total*100:.1f}%)")
    print(f"No impulse: {none:,} ({none/total*100:.1f}%)")
    print(f"Date range: {combined['timestamp'].min()} → {combined['timestamp'].max()}")
    
    # Feature columns (everything except metadata)
    meta_cols = ['timestamp', 'symbol', 'label', 'bars_to_target', 'move_size', 'open', 'high', 'low', 'close', 'volume']
    feature_cols = [c for c in combined.columns if c not in meta_cols]
    print(f"Features: {len(feature_cols)}")
    
    # Save
    os.makedirs(os.path.dirname(args.output) or '.', exist_ok=True)
    combined.to_csv(args.output, index=False)
    print(f"\nSaved → {args.output} ({os.path.getsize(args.output) / 1024 / 1024:.1f} MB)")
    
    conn.close()


if __name__ == '__main__':
    main()
