# Survey: GitHub `chart-pattern` / `chart-patterns` topics

**Date:** 2026-05-11
**Branch:** feature/chart-pattern-eval
**Topics surveyed:** https://github.com/topics/chart-pattern and https://github.com/topics/chart-patterns
**Verdict:** Two new candidates worth noting; **port plan unchanged**.

---

## Triage of top results

Most repos on these topics are either:
- Empty / spam (recent 0-star "AI Crypto Pattern Scanner 2026" repos with no code)
- Already evaluated (`BennyThadikaran/stock-pattern`, `BennyThadikaran/precise-patterns`)
- MQL5 forex bots (`EarnForex/*`) — not applicable to our JVM stack
- YouTube channel companion code (`zeta-zetra/code`) — duplicates of `zeta-zetra/chart_patterns`

After filtering, two genuine candidates:

| Repo | Stars | Lang | License | Domain | Verdict |
|---|---|---|---|---|---|
| **zeta-zetra/chart_patterns** | 100 | Python | None (no LICENSE file) | Chart patterns | Reject — no license; useful methodology note |
| **RauchenwaldC/motivewave-candlestick-pattern-study** | 8 | **Java** | **MIT** | Candlestick patterns (different domain) | Reject — wrong domain, MotiveWave-coupled |

---

## `zeta-zetra/chart_patterns` — 100★ Python, no license

**Patterns covered:** Doubles, Flag, Head & Shoulders, Inverse H&S, Triangles, Pennant. Subset of BennyThadikaran's coverage; missing VCP and harmonics.

**What's interesting — different methodology:** Uses **`scipy.stats.linregress`** to fit trendlines through pivot points, then validates with **R² goodness-of-fit threshold** + slope limits. Example signature:
```python
find_triangle_pattern(ohlc, lookback=25, min_points=3, rlimit=0.9,
                      slmax_limit=0.00001, slmin_limit=0.00001,
                      triangle_type="ascending")
```

This is conceptually closer to our existing `TrendlineV2` + `SlopedLineDetector` (which fit lines through pivots and validate touch points) than BennyThadikaran's pure 6-point geometry.

**Why we still don't adopt:**
1. **No LICENSE file.** Public repo without license = "all rights reserved" by default. Cannot redistribute, modify, or include in any product without explicit permission.
2. **Methodology overlap:** the regression-fit approach is already represented in our codebase (TrendlineV2, SlopedLineDetector). We'd duplicate, not add.
3. **Smaller pattern coverage** than the library we're already porting.

**Salvageable idea:** The **R²-goodness-of-fit gate** for trendlines is worth checking against our existing detectors. If `TrendlineV2` doesn't already require R² above some threshold for a line to count as "well-fitted," that's a small quality enhancement to add to our own engine — independent of any port.

---

## `RauchenwaldC/motivewave-candlestick-pattern-study` — 8★ Java, MIT

**Wrong domain.** This is a **CANDLESTICK** pattern library — Hammer, Doji, Engulfing, Three Black Crows, Marubozu, etc. — 33+ patterns on 1-3 bars. Not the same as **chart patterns** (Triangle, H&S, VCP) which need 20-100+ bars of pivot structure.

**Architecture issue:** Single 54 KB `CandlestickPatterns.java` file wired to the **MotiveWave SDK** (a proprietary charting platform). To extract the algorithms we'd have to strip out the MotiveWave dependencies; not worth it because:

1. **We already have candlestick patterns via ta4j.** Existing in our classpath: `BullishEngulfingIndicator`, `BearishEngulfingIndicator`, `DojiIndicator`, `BullishHaramiIndicator`, `BearishHaramiIndicator`, `ThreeBlackCrowsIndicator`, `ThreeWhiteSoldiersIndicator`, plus various rule combinators. ta4j gives us the same coverage in a properly-tested library.
2. Candlestick patterns are a different problem class from chart patterns. We can add candlestick rules to our strategies without writing a new pattern engine.

**MIT license is friendly**, but the wrong domain and tight MotiveWave coupling make it not worth extracting.

---

## Survey summary table — all libraries reviewed to date

| Library | Stars | License | Domain | Code quality | Verdict |
|---|---|---|---|---|---|
| white07S/TradingPatternScanner | 287 | CC BY-NC-SA 4.0 | Chart | Poor (naive masks) | Reject |
| MarcosACH/chart-patterns | 2 | None | Chart (Pine Script) | N/A — TradingView only | Reject |
| **BennyThadikaran/stock-pattern** | **373** | **GPL-3.0** | **Chart** | **High (6-point geometry)** | **Porting — Phase 1 ✓** |
| BennyThadikaran/precise-patterns | 17 | GPL-3.0 | Chart (streaming WIP) | Scaffolding only, no algos | Hold; re-eval later |
| zeta-zetra/chart_patterns | 100 | None | Chart (regression-fit) | Reasonable methodology | Reject (no license) |
| RauchenwaldC/motivewave-candlestick | 8 | MIT | **Candlestick** (different) | Wrong domain | Reject |

## Recommendation
Continue with the BennyThadikaran port. The survey confirmed it's the highest-quality option with a usable (if viral) license and the broadest pattern coverage. The two new candidates don't change that calculus.

**One small action item to spin out independently:**
- After Phase 2 of our port lands, verify `TrendlineV2` enforces R² goodness-of-fit. If not, add the threshold as an enhancement — concept from `zeta-zetra/chart_patterns`.

---

*Evaluated by: Claude (gh API + source inspection).*
