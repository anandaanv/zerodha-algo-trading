# Head and Shoulders / Inverted Head and Shoulders

## What it is
A 5-pivot reversal pattern with a higher middle peak (Head) flanked by two lower peaks (Shoulders). Inverted H&S is the bullish mirror image with a lower middle trough.

## Detection Rules

### Pivot sequence required
- **H&S (bearish)**: High → Low → Higher High → Low → High (5 pivots)
  - p0 = Left Shoulder top
  - p1 = Left Shoulder bottom
  - p2 = Head top (must be higher than both shoulders)
  - p3 = Right Shoulder bottom
  - p4 = Right Shoulder top (within 4% of p0)

- **Inverted H&S (bullish)**: Low → High → Lower Low → High → Low (5 pivots, mirrored)

### Shoulder symmetry
- Both shoulder tops (p0 and p4) within **4%** of each other.

### Neckline
- Drawn between the two shoulder bottoms (p1 and p3) — may be slightly slanted.
- On the chart: drawn as a trend_line between these two points (not a horizontal line).

### Target
- Pattern height = Head − neckline midpoint
- Target = neckline breakout point − pattern height (H&S) or + height (Inverted H&S)

## Status lifecycle
1. **BUILDING** — Left shoulder and head identified.
2. **WATCHING** — Right shoulder forming; neckline established.
3. **CONFIRMED** — Price breaks below neckline (H&S) or above neckline (Inverted).

## Confidence scoring
- Base: 65
- +10 if RSI divergence (head RSI lower than left shoulder RSI for H&S)
- +10 if volume is lighter on right shoulder than left
- +5 if neckline is horizontal (not slanted)

## Elliott wave context
- H&S typically forms at W5 completion → bearish A-wave correction follows.
- Inverted H&S forms at WC/W2 completion → bullish impulse follows.

## What to watch for (invalidation)
- Right shoulder exceeds head height → pattern breaks down.
- Price reclaims neckline after breakout (throwback above neckline for H&S is common but should not sustain).
