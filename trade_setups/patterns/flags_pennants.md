# Flags and Pennants

## What it is
Short consolidation patterns that form after a sharp directional move (the pole). Signal continuation of the prior trend.

## Detection Rules

### Pole detection
The pole is the sharp impulse move before the consolidation:
- **Bullish pole**: MACD > Signal AND RSI > 70 during the pole bars.
- **Bearish pole**: MACD < Signal AND RSI < 30 during the pole bars.
- Pole must be a significant, swift move (not a gradual trend).

### Flag criteria
After the pole:
- Price enters a range-bound consolidation.
- Max range of the flag = `maxRangePercentage` of the pole height.
- Indicator values (RSI, MACD) should be declining during the flag (momentum cooling off).
- Both upper and lower trendlines are found with at least **3 touch points each**.
- Trendlines are roughly **parallel** (flag body is rectangular or slightly angled).

### Pennant criteria
Same as flag, but:
- The two trendlines **converge** (symmetrical triangle shape in the consolidation).
- Consolidation is typically shorter in time and price than a flag.

## Status lifecycle
1. **BUILDING** — Pole detected; consolidation beginning.
2. **WATCHING** — Flag/pennant structure clear; waiting for breakout.
3. **CONFIRMED** — Price breaks out beyond the flag boundary in direction of the pole.

## Target
- Measured move = pole length projected from the breakout point.

## Elliott wave context
- Flag = **Wave 4** consolidation within an impulse → breakout is Wave 5.
- Pennant = same, but tighter → sharper Wave 5 thrust.
- Target of Wave 5 ≈ origin of Wave 4 + pole length.

## What to watch for (invalidation)
- Flag/pennant takes too long (more than the pole's duration) → exhaustion, not continuation.
- Price breaks opposite to the pole direction.
- Volume should contract during flag and expand on breakout.
