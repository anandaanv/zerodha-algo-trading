# Elliott Impulse Wave Detection (W1–W5)

## What it is
A 5-wave structure in the direction of the trend: three motive waves (1, 3, 5) separated by two corrective waves (2, 4). The backbone of Elliott Wave Theory.

## Hard Rules (violations invalidate the count)

### Wave 2
- Cannot retrace 100% or more of Wave 1 (W2 cannot go below the start of W1).
- If violated: count is discarded.

### Wave 3
- Must be longer than at least one of Wave 1 or Wave 5 (cannot be the shortest of all three motive waves).
- If W3 < W1 AND W3 < W5: hard violation → Fibonacci score set to 0.

### Wave 4
- Cannot overlap Wave 1's price territory (W4 low cannot go below W1 high in a bull impulse).
- **Exception**: Diagonals ALLOW Wave 4 to overlap Wave 1 — this is in fact required for diagonals.
- If overlapping in a standard impulse: counts as a rule violation, not discarded but penalised heavily.

## Fibonacci Scoring (0–42 points)

### Wave 2 retracement vs ideal 61.8% of Wave 1
- Within ±5%: 8 pts
- Within ±10%: 5.6 pts
- Within ±15%: 3.2 pts

### Wave 3 extension vs ideal 161.8% of Wave 1
- Within ±5%: 16 pts (highest weight — W3 extension is the most important ratio)
- Within ±10%: 11.2 pts
- Within ±15%: 6.4 pts

### Wave 4 retracement vs ideal 38.2% of Wave 3
- Within ±5%: 8 pts
- Within ±10%: 5.6 pts
- Within ±15%: 3.2 pts

### Wave 5 (best match from 4 targets)
- vs 61.8% of W1: 8 pts
- vs 100% of W1: 6 pts
- vs 161.8% of W1: 4 pts
- vs 61.8% of (W1 + W3 net distance): 6 pts
- Takes the highest matching target.

## Indicator Scoring (0–45 points)

### Wave 3 signals
- EWO (MACD 5,35) peak at W3 pivot (W3 EWO > W1 EWO and W3 EWO > W5 EWO): up to **10 pts** (magnitude-weighted)
- Bollinger Band walk at W3 (price > upper band): **7 pts**
- Volume expansion during W3 vs W1: **4 pts**

### Wave 4 signals
- Stochastic K < 30 at W4 low (oversold): **7 pts**
- MACD amplitude contracting at W4 (< 50% of W3 amplitude): **8 pts**
- MACD hovering near zero at W4 (< 20% of peak amplitude): **7 pts** — the "MACD near zero" W4 signature

### Wave 5 signals
- MACD divergence at W5 (W5 MACD < W3 MACD despite higher price): up to **10 pts**
- Volume diminishing at W5 vs W3: **4 pts**
- RSI divergence at W5 (W5 RSI < W3 RSI for bullish impulse): **5 pts**

## Cross-Timeframe Scoring (0–20 points)
Validates the internal sub-wave structure on a lower timeframe:

### Motive waves (W1, W3, W5)
- Child TF shows 5, 7, 9, or 11+ swings (odd count ≥ 5) = impulse sub-structure confirmed
  - W1 match: +8 pts
  - W3 match: +10 pts (strongest weight — W3 should be impulsive)
  - W5 match: +6 pts
- Child TF shows only 3 swings in a motive wave = contradiction (potential diagonal)

### Corrective waves (W2, W4)
- Child TF shows exactly 3 swings = corrective sub-structure confirmed: +8 pts each
- Child TF shows 5+ swings in W2/W4 = contradiction (impulsive correction — possible nested structure)

## Alternation (0–10 points)
Wave 2 and Wave 4 should alternate in character:
- If W2 is sharp (zigzag, deep retrace), W4 should be flat/shallow
- If W2 is shallow (38.2%), W4 should be deep (50%+)
- Detected and scored — does not invalidate, just boosts confident alternating counts.

## Current wave in progress
After the last confirmed pivot, the engine tracks:
- Which wave number is currently developing.
- A dashed projection line is drawn from the last pivot to current price on the chart.

## Total score components
```
Total = Fibonacci (0–42) + Indicators (0–45) + CrossTF (0–20) + Alternation (0–10) + Proportionality bonus (0–15)
Max theoretical: ~132 points
```
Top 3 non-historical counts shown on chart, ranked by total score.
