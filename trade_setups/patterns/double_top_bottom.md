# Double Top / Double Bottom

## What it is
Two consecutive peaks (Double Top) or troughs (Double Bottom) at approximately the same price level, forming an M or W shape. Signals a reversal of the prior trend.

## Detection Rules

### Pivot sequence required
- **Double Bottom**: Low → High → Low (3 pivots). Both lows within 4% of each other.
- **Double Top**: High → Low → High (3 pivots). Both highs within 4% of each other.

### Tolerance
- Both peaks/troughs must be within **4%** of each other (`EQUAL_LEVEL_TOLERANCE`).
- The bounce between them (neckline retrace) must be at least **5%** of the price.

### Neckline
- The high between the two lows (Double Bottom) or low between two highs (Double Top).
- Breakout above/below the neckline confirms the pattern.

### Target
- Pattern height = neckline − bottom (or top − neckline for Double Top)
- Target = neckline + pattern height (projected measured move)

## Status lifecycle
1. **BUILDING** — First pivot detected, waiting for the second.
2. **WATCHING** — Both pivots and neckline identified; waiting for neckline breakout.
3. **CONFIRMED** — Price breaks and closes beyond the neckline.

## Confidence scoring (0–95)
- Base: 60
- +10 if RSI divergence (second bottom has higher RSI than first despite similar price)
- +10 if MACD divergence
- +5 if ADX < 25 (ranging market, pattern more reliable)

## Indicator confluence
- RSI divergence is the strongest confirming signal.
- Low ADX (<25) means market was range-bound — ideal for this pattern.
- MACD cross above signal line near second bottom = added confirmation.

## Elliott wave context
- Double Bottom often forms at W2, W4, or WC completion → next move is impulse (W3, W5).
- Double Top often forms at W5 completion → A-wave correction follows.

## What to watch for (invalidation)
- Second pivot breaks significantly beyond the first (more than 4% tolerance) → not a valid double.
- Volume should diminish on the second peak/trough vs the first (confirms exhaustion).
