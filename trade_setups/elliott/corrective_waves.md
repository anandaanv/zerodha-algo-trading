# Elliott Corrective Wave Detection (Zigzag, Flat, Triangle)

## What it is
After a 5-wave impulse, price corrects in a 3-wave structure (A-B-C) or variation. Identifying the corrective type helps predict the next impulse direction and target.

---

## Zigzag (ABC) — Sharp Correction

### What it looks like
A sharp, deep counter-trend move. Wave A down, Wave B partial retrace up, Wave C continues down (to or beyond A's low). W shape from the top.

### Detection rules
- **Wave A**: First leg against the prior trend.
- **Wave B retracement**: Must be **≤ 78.6%** of Wave A. If B exceeds 78.6% of A, it's a flat, not a zigzag.
- **Wave C length**: Between **61.8% and 161.8%** of Wave A length. Ideal is 100% (equal to A).
- Ideal B retrace: **61.8%** of A.

### Fibonacci scoring
- B retrace vs 61.8%: up to 12 pts
- C/A ratio vs 100%: up to 12 pts

### Indicator confluence
- EWO divergence (C vs A at wave ends): +8 pts
- RSI divergence (C vs A): +7 pts
- Low ADX (<25) — range-bound = correction environment: +5 pts

### Wave context
- Sharp, deep Wave 2 is typically a zigzag.
- If Wave 2 is zigzag, expect Wave 4 to be a flat (alternation principle).

---

## Flat (ABC) — Sideways Correction

### What it looks like
A more sideways, consolidating correction. Wave B retraces nearly all of Wave A (often exceeds it in an expanded flat), then Wave C returns to roughly Wave A's end.

### Detection rules
- **Wave B retracement**: **≥ 90%** of Wave A. This distinguishes flat from zigzag.
- **Expanded Flat**: Wave B exceeds the origin of Wave A (B > 100% of A) — common and normal.
- **Running Flat**: Wave C doesn't fully retrace Wave A (truncated C) — bullish signal in a bull market.
- **Wave C**: Approximately equal length to Wave A.

### Fibonacci scoring
- B retrace vs 100%: up to 12 pts
- C/A ratio vs 100%: up to 12 pts

### Wave context
- Flat is the typical Wave 4 structure (especially after a zigzag Wave 2 — alternation).
- Expanded flats are common in strong trending markets (B exceeds A origin = underlying trend is strong).

---

## Triangle (ABCDE) — Converging Correction

### What it looks like
A 5-swing converging structure (A-B-C-D-E) forming a tightening range. Common as Wave 4 before a final Wave 5 thrust, or as Wave B in a complex correction.

### Detection rules
- 5 swings (A through E), each one smaller than the prior in the same direction.
- Converging trendlines connecting A-C-E (one side) and B-D (other side).
- Duration is typically long — triangles take time.

### Target after triangle breakout
- The thrust out of the triangle (Wave 5 or final leg) equals approximately the widest point of the triangle (A−B distance at the left).

### Wave context
- **Wave 4 triangle**: Very common. After the thrust (Wave 5), expect reversal.
- **Wave B triangle**: Signals a complex correction; Wave C typically sharp and swift.

---

## Double Zigzag (WXY)

### What it looks like
Two zigzags connected by an X-wave. Produces a larger, deeper corrective move.

### Detection rules
- First zigzag (W), connector wave (X, typically retraces 50–70% of W), second zigzag (Y).
- Y wave often equals W in length.
- More complex and harder to identify in real-time.

### Wave context
- Often forms when a simple zigzag seems "too short" for the degree expected.
- Common as Wave 2 at a larger degree in strong trending markets.

---

## Status tracking
Corrective waves are tracked with the same lifecycle:
1. **BUILDING** — Wave A forming.
2. **WATCHING** — Full ABC visible, awaiting next impulse.
3. No explicit CONFIRMED state; completion is inferred from subsequent impulse beginning.

---

## Key distinguishing ratios

| Structure | B-wave retrace | C-wave length |
|-----------|---------------|---------------|
| Zigzag | ≤ 78.6% of A | 61.8–161.8% of A |
| Flat | ≥ 90% of A | ≈ 100% of A |
| Expanded Flat | > 100% of A | > 100% of A |
| Running Flat | ≥ 90% of A | < 61.8% of A |
