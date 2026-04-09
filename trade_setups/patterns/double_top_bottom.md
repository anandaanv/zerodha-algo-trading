# Double Top / Double Bottom

## What it is
Two consecutive troughs (Double Bottom) or peaks (Double Top) at approximately the same price level, forming a W or M shape. Signals a reversal of the prior trend.

---

## Double Bottom — Formation Rules

### Pivot sequence
Low(1) → High (neckline) → Low(2)

### Second low — Fibonacci requirement
Low(2) must retrace **62%–100%** of the first swing (Low(1) → Neckline).
- Deeper retracement = stronger pattern (100% retrace = equal lows, most common).
- Below 62%: pattern is too shallow — not valid.
- The 62%–78.6% zone is acceptable but weaker. 78.6%–100% is ideal.

### Equal level tolerance
Both lows must be within **4%** of each other in absolute price terms.

### Neckline
The swing high between the two lows. Acts as resistance. Breaking it is the measured move trigger.

### Target (measured move)
Pattern height = Neckline − Low(1)
Target = Neckline + Pattern Height

---

## Double Top — Formation Rules (mirror of Double Bottom)

### Pivot sequence
High(1) → Low (neckline) → High(2)

### Second high — Fibonacci requirement
High(2) must retrace **62%–100%** of the first swing (High(1) → Neckline).
Deeper retrace = stronger. Same 4% tolerance on the highs.

### Target
Pattern height = High(1) − Neckline
Target = Neckline − Pattern Height

---

## Status Lifecycle

### BUILDING
- First low (or high) detected.
- Waiting for the bounce and second pivot to form within Fib range.

### C1-WATCHING
- A reversal candlestick pattern has **completed** at the second low (or high).
- See `candlestick_patterns.md` for valid patterns (hammer, bullish engulfing, piercing, etc.).
- "Completed" means the closing candle has confirmed the pattern — not just the first candle of a two-candle pattern.
- Waiting for the candlestick pattern breakout to trigger entry.

### C3-WATCHING
- Neckline has been broken (breach within 1 ATR of neckline still counts as valid — temporary overshoot is acceptable).
- Price has pulled back to the neckline zone (within 1 ATR).
- A reversal candlestick pattern has **completed** on this neckline retest.
- Waiting for the candlestick pattern breakout to trigger entry.

---

## Entry Mechanics (same for C1 and C3)

1. Reversal candlestick pattern completes → state moves to WATCHING.
2. Next candle breaks out above the pattern's key level (see `candlestick_patterns.md` for breakout level per pattern type).
3. **Entry**: Open of the candle immediately after the breakout candle.

---

## ATR tolerance (C3 only)
- Neckline is not a precise line — it is a zone of ±1 ATR.
- A temporary neckline breach (price dips slightly below then recovers) is valid if it stays within 1 ATR.
- The retest in C3 is also considered valid if price comes within 1 ATR of the neckline.

---

## Indicator confluence (boosts confidence)
- **RSI divergence**: Low(2) has higher RSI than Low(1) despite similar/lower price → strongest confirmation signal.
- **MACD divergence**: MACD histogram higher at Low(2) than Low(1).
- **Low ADX** (<25): Ranging market — pattern more reliable in consolidation.
- **Volume**: Should be lower on Low(2) than Low(1) — declining selling pressure.

---

## Elliott wave context
- Double Bottom often marks end of Wave 2, Wave 4, or Wave C → next move is an impulse (Wave 3, Wave 5).
- Double Top often marks end of Wave 5 or Wave B terminus → A-wave correction follows.

---

## Invalidation
- Low(2) breaks below Low(1) by more than 4% tolerance AND outside ATR range → pattern fails.
- Price breaks neckline convincingly to the downside after a Double Bottom setup.
