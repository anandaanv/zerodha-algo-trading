# ZigZag Pivot Detection

## What it is
The foundation of all Elliott wave and pattern analysis. Filters out minor noise and identifies significant swing highs and swing lows (pivots) in price action.

## How pivots are detected

### Step 1: Volatility measurement
For each bar, compute:
- **True Range (TR)** = max(High−Low, |High−PrevClose|, |Low−PrevClose|)
- **ATR (14)**: First 14 bars use simple average; after that, exponential smoothing with alpha = 2/(14+1)
- **Relative Volatility (RVOL)**: EMA of (TR/Close) over 14 bars — normalizes ATR as a % of price

### Step 2: Reversal threshold
The minimum price move needed to declare a reversal:
```
threshold = max(atrMult × ATR, pctMin × Price)
```
Defaults: `atrMult = 1.5`, `pctMin = 0.5%`

When relative volatility is enabled:
```
threshold = max(volMult × RVOL × Price, pctMin × Price)
```

### Step 3: Hysteresis (anti-whipsaw)
Reversals require the move to exceed `threshold × hysteresis` (default **1.2×**) before confirming. This prevents back-and-forth flipping in choppy markets.

### Step 4: Minimum bar spacing
At least **5 bars** must separate consecutive pivots (`minBarsBetweenPivots`). Prevents too many closely-spaced pivots on fast timeframes.

### Step 5: Pivot confirmation
- **High pivot**: Current bar's high is the extreme, and a reversal down of threshold × hysteresis has begun.
- **Low pivot**: Current bar's low is the extreme, and a reversal up of threshold × hysteresis has begun.

## Retracement % computed at each pivot
Used by pattern detection and Elliott wave scoring:
```
For Low(k) following High(k-1):
  retracement = (High(k-1) − Low(k)) / (High(k-1) − Low(k-2)) × 100

For High(k) following Low(k-1):
  retracement = (High(k) − Low(k-1)) / (High(k-2) − Low(k-1)) × 100
```

## Extension % computed at each pivot
```
For High(k) in UP swing:
  extension = (High(k) − Low(k-1)) / (High(k-2) − Low(k-3)) × 100

For Low(k) in DOWN swing:
  extension = (High(k-1) − Low(k)) / (High(k-3) − Low(k-2)) × 100
```

## Live / LTP pivot
A synthetic current-bar pivot is appended based on the latest price:
- If last direction was UP and LTP > last high: extend the high pivot to LTP.
- If last direction was DOWN and LTP < last low: extend the low pivot to LTP.
- Otherwise: append a new tentative pivot in the opposite direction.

## Caching (TTL per timeframe)
- 1m: 1 minute
- 5m: 5 minutes
- 1h: 60 minutes
- Daily: 24 hours
- Weekly: 7 days
