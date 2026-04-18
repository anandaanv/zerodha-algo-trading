# Impulse Wave Trading System — Architecture & Implementation Notes

## Session: April 16-18, 2026

This document captures all key decisions, findings, and open issues from the impulse wave detection and trading system build session.

---

## 1. Labeling Evolution

### Starting point: ChoCH-based labeling
- Initial approach: use Change of Character (ChoCH) events as wave boundaries
- **Key insight**: ChoCH is a CONFIRMATION pivot, not the true reversal extreme. The real reversal is the pivot IMMEDIATELY PRECEDING the ChoCH.
- Fix: anchor on reversal extremes → labels doubled from 10 to 19 W3 labels on NIFTY50 1h

### Final: Historical DP-scan labeler (`HistoricalImpulseLabeler`)
- Enumerates ALL valid (origin, W1_end, W2_end) triples across the pivot list
- No ChoCH dependency — pure pivot-space search
- W3 end: global extreme (no 50% retrace termination — user's explicit decision)
- Dedup by `(W3_end_idx, direction)`, keep max W3 size → canonical labels
- Raw variant (`historical-raw`): no dedup, all valid triples → used for training

### Labeling rules (unchanged)
- W2 retrace: 50–99% of W1 (≥61.8% = HIGH confidence)
- W3 ratio: ≥1.61× W1 (NOT softened — user's explicit decision)
- W4 retrace: 23.6–50% of W3
- W4 must not overlap W1 end (Elliott hard rule)
- W5: ≥0.618× W3

---

## 2. Feature Engineering (283 features)

### Feature groups
1. **Current pivot (20)**: direction, retrace%, leg_size%, leg_duration, leg_speed, EMA 20/50/200 dist, RSI, stoch K/D/cross, MACD line/signal/hist, ADX, +DI/−DI, BB position/bandwidth
2. **Prior 8 pivots (160)**: same 20 features each (5 structural + 15 indicators)
3. **Older pivots 9-20 (60)**: 5 structural features each (no indicators)
4. **Market structure (4)**: trend_state, trend_streak, bars_since_reversal, last_break_type
5. **Higher timeframe (16)**: full indicator stack on parent TF
6. **Lower timeframe (16)**: full indicator stack on child TF
7. **Derived (7)**: leg_size_vs_avg, leg_duration_vs_avg, bars_since_large_leg, max/min_retrace_last10, leg_size_stddev, legs_contracting

### Key finding
- Adding 12 current-pivot indicators (were missing!) improved PF from 3.65 → 9.67 at thr 0.97
- `curr_direction` dominates feature importance at 0.25 gain — it's a ZigZag direction metric, NOT the label leak

### `bars_to_target` column
- Records bars from W2 end (K) to the first bar where price touches 1.61×W1 target
- Uses `tsToIdx` map (not `ZigZagPoint.barIndex` which is always 0 due to Lombok builder not triggering private setter)
- Used for fast-impulse filtering at training time

---

## 3. Model

### Architecture
- **XGBoost 3-class classifier**: {no_impulse=0, wave3_bullish=1, wave3_bearish=2}
- n_estimators=200, max_depth=6, learning_rate=0.1, multi:softprob
- Sample weights for class imbalance
- Chronological train/test split (70/30) — NEVER random

### Training data
- Strategy: `historical-raw` (no dedup, all valid triples)
- Timeframe: 15-minute
- Universe: 249 FnO stocks
- ~27,430 total rows, ~12,695 impulse labels

### Direction prediction
- The `direction` column in CSV is EXCLUDED from features (it's a leak — set by labeler, perfectly identifies the label)
- Direction comes from the model itself: argmax(P_bullish, P_bearish) determines LONG/SHORT
- Earlier binary model had inverted directions (catastrophic -459% P&L); 3-class fixed this

---

## 4. Exit Strategy: Fibonacci Slab Trail

### Slabs (W1 multiples)
```
[1.0, 1.382, 1.618, 2.0, 2.382, 2.618, 3.0, 3.382, 3.618]
```

### Rules
1. Initial stop: W1 origin = `entry ± w1_size × (1 - retrace_pct/100)`
2. When price reaches slab N: lock stop at slab N price. Aim for slab N+1.
3. At 3.618× (final slab): exit immediately
4. Timeout: 200 bars

### Why slab trail beats alternatives
- **vs fixed 1.61× target**: +404% vs +202% total (doubles return)
- **vs BE@15% + fixed target**: higher total return, slightly more DD
- **vs ML-predicted targets**: 10-class predictor couldn't beat fixed targets
- **vs RL exit**: RL learned to exit too early (avg 3 bars); fixed exits capture asymmetric R:R

### Breakeven activation
- After price moves 15% of target distance → move stop to breakeven (entry price)
- Proven: reduces avg loss from -0.75% to -0.17%, PF from 3.25 to 7.98
- Fib@0.618 retrace exit was validated as the natural wave boundary
- User's insight: "if the stock reverses after 61% exit, it will qualify for a new trade" — confirmed: 92% of re-entries after Fib exit are same-direction, 100% of same-dir chains are profitable

---

## 5. Backtesting Results

### FnO 15m, threshold 0.97, slab trail (OOS)
| Metric | Value |
|---|---|
| Trades | 366 |
| Win rate | 48.9% |
| Avg winner | +2.75% |
| Avg loser | -0.51% (ORIG_STOP) |
| Profit factor | 5.59 (gross) |
| Total return | +404% |
| Max drawdown | 7.38% |
| Avg hold | 0.52 days |

### Monthly option P&L (+2 strikes OTM, 20 DTE)
| Metric | Value |
|---|---|
| EV per trade | +11.8% of premium |
| At ₹50K/trade × 171 trades | ₹10.1 lakh/month |
| Avg winner on premium | +41.4% |
| Avg loser on premium | −18.9% |

### Threshold sweep highlights
| Threshold | Trades | PF | Net/trade |
|---|---|---|---|
| 0.90 | 1,733 | 3.91 | +0.37% |
| 0.95 | 804 | 3.64 | +0.50% |
| 0.97 | 366 | 5.59 | +1.10% |
| 0.99 | 48 | 7.48 | +0.66% |

---

## 6. RL Experiments (all shelved)

### Exit timing RL
- PPO 300k steps, 1h data, theta_factor=0.20
- Result: avg 2.6 bars held (too early), total return 171% vs fixed 760%
- Agent learned scalp behavior — same PF (3.30) but way less total return

### Trailing stop RL
- New env: agent controls only stop (HOLD_STOP / TIGHTEN_STOP)
- Created `TrailingStopEnv` with breakeven + trailing logic
- Not fully evaluated — shelved in favor of rule-based slab trail

### Profit extension RL
- Env starts at target-hit bar, agent rides extensions
- Result: +0.607% extra per trade, avg 1 bar held
- Neutral — not worth the complexity vs simple trailing

### Conclusion
**Simple Fibonacci slab trail outperforms all RL variants.** RL consistently learned to exit too early, forfeiting the asymmetric R:R that makes the strategy profitable.

---

## 7. TLB Pivot Analysis

### Enriched CSV (34 new columns at P1 and P2)
- Stoch K/D, ADX/+DI/−DI, BB width/%B, EMA 20/50/200 dist, volume ratio, ATR at both pivot bars
- RSI/MACD/volume divergence between P1 and P2
- P1-P2 bar distance
- Code: `PatternComboBacktestService.java` — 13 new compute methods

### TLB winner vs loser profile
Significant indicators at the ENTRY point (not pivots — that analysis was incorrect first time):
- **BB %B (watching)**: winners 0.70 vs losers 0.59 (p=0.0005)
- **Stochastic RSI K**: winners 0.64 vs losers 0.57 (p=0.0014)
- **RSI slope**: winners 1.32 vs losers 0.69 (p=0.0064)
- **BB aligned**: winners 0.77 vs losers 0.70 (p=0.0069)

### What's NOT significant for TLB
- RSI absolute levels (p=0.19)
- ADX (p=0.08)
- MACD histogram (p=0.55)
- RSI divergence (p=0.27)
- P1-P2 distance (p=0.96)

---

## 8. Infrastructure Built

### Java
- `ImpulseLabelStrategy` interface + 3 implementations (pivot-scan, choch-state, historical)
- `ExitStrategy` interface → `DtbExitStrategy`, `ImpulseSlabExitStrategy`
- `ExitStrategyRouter` — dispatches by `StrategyType` enum
- `ImpulseSignalScanner` — @Scheduled every 15m, scans FnO universe
- `ImpulseStrikeSelector` — picks OTM monthly option
- `TradeActionLog` entity + `TradeActionLogger` — universal audit trail
- `BarSeriesTruncator` — prevents forward data leakage in simulation
- `TradeSimulationService` — time-bounded replay harness
- `SimulationController` — REST API for simulation
- `SimulationStrategy` interface — strategy-agnostic simulation
- `ImpulseSimulationStrategy` — impulse-specific simulation logic
- `ChartDataController` — `fetchLatest=false` flag for DB-only OHLC reads
- `InstrumentResolverService` — OTM option resolution with `otmPct` parameter

### Python
- `prediction_server.py` — unified FastAPI serving 7 models on port 8501
- `train_impulse_model.py` — 3-class XGBoost trainer with bars_to_target filter
- `backtest_impulse_model.py` — backtest with 1.61×W1 target + W1-origin stop
- `_creds.py` — reads .local-dev-credentials, logs in for fresh JWT
- RL envs: `exit_env.py`, `trailing_stop_env.py`, `profit_extension_env.py`

### UI
- `ZigZagViewer.tsx` — faint candles + ZigZag line + reversal extreme markers + ChoCH toggle
- `TradeActionTimeline.tsx` + `TradeActionTimelineDark.tsx` — expand/collapse action log on trades page
- Dashboard card for ZigZag viewer

---

## 9. Repo Structure

### Public repo: `anandaanv/zerodha-algo-trading`
Infrastructure only — no proprietary strategy code. Interfaces, simulation framework, trade management, UI.

### Private repo: `anandaanv/algo-strategies-private`
All strategy implementations, models, training scripts, design docs. Linked as git submodule at `strategies/`.

### Setup on any machine
```bash
git clone git@github.com:anandaanv/zerodha-algo-trading.git
cd zerodha-algo-trading
git submodule update --init
cd strategies && ./setup.sh  # creates symlinks
```

---

## 10. Deployment (EC2)

### Services
- **Tomcat**: WAR deployment at `/kitecon`, nginx proxy at `/api/`
- **trade-filter**: Flask ML service on port 5001 (DTB models)
- **impulse-predictor**: FastAPI ML service on port 8501 (impulse + all models)

### Config (`application.properties`)
```properties
impulse.enabled=true
impulse.threshold=0.97
impulse.prediction.url=http://localhost:8501/predict-batch
impulse.max.trades.per.symbol=2
impulse.otm.distance.pct=3
impulse.paper.trade=false
impulse.capital.per.trade=25000
```

### Deploy script
```bash
bash /opt/deploy.sh  # existing: git pull + WAR build + Tomcat restart
# Then manually: strategies/setup.sh + systemctl restart impulse-predictor
```

---

## 11. Open Issues / Bugs

### trade_order creation in simulation
- **Bug**: `parent_order_id` column NOT NULL in `trade_order` — leftover from legacy `Order` entity table-name collision
- **Root cause**: both `Order` (legacy, now mapped to `legacy_order`) and `TradeOrder` entities were mapping to `trade_order` table. Hibernate `ddl-auto=update` created zombie columns including `parent_order_id NOT NULL`.
- **Fix applied locally**: ALTER TABLE + entity rename. Not yet working on EC2 (Hibernate caches schema).
- **Proper fix needed**: either drop the zombie column entirely, or ensure the legacy `Order` entity's `@Table(name = "legacy_order")` annotation is deployed and Hibernate stops touching `trade_order`.

### Simulation action log timestamps
- Action log entries use `Instant.now()` (wall-clock time) instead of simulated time
- Cosmetic issue — P&L and slab data are correct, only timestamps are wrong
- Fix: pass simulated time from `SimulationClock` to `TradeActionLogger`

### Simulation speed
- 30 symbols × 5881 steps = ~50 seconds (after optimization: ZigZag every 4 bars)
- Pre-optimization was ~15 minutes
- Further optimization possible: batch prediction calls, cache indicators

### Prediction server model naming
- EC2: model loaded as `impulse_15m_fno_283feat` (filename-based)
- Java code calls default `/predict-batch` which routes to `impulse` model
- Fix: symlink `impulse.pkl` → `impulse_15m_fno_283feat.pkl` on EC2 (done)

---

## 12. Key Decision Log

| Decision | Rationale | Date |
|---|---|---|
| Use reversal extremes, not ChoCH pivots | ChoCH is confirmation, not the turn | Apr 16 |
| Historical DP-scan over ChoCH state machine | Greedy state machine misses valid counts | Apr 16 |
| Global extreme for W3 end (no retrace stop) | RELIANCE example: 52% internal retrace killed valid impulse | Apr 16 |
| raw (no-dedup) dataset for training | Deduped too sparse (200 labels → model collapses) | Apr 16 |
| 3-class model (not binary) | Direction inversion bug in binary model | Apr 16 |
| Keep W3 ratio at 1.61× | User's explicit decision — don't soften | Apr 16 |
| Fib slab trail over fixed target | +404% vs +202% total return | Apr 17 |
| Fib slab trail over RL exit | RL exits too early, forfeits R:R | Apr 17 |
| 15m timeframe for options | 1h too slow (4-day hold), 15m gives 0.5-day hold | Apr 17 |
| +2-3% OTM monthly options | Higher % return on premium, manageable theta | Apr 17 |
| Private submodule for strategy code | Public repo stays useful, edge stays private | Apr 18 |
| EC2: inference only, no training | Models trained locally, deployed via git | Apr 18 |

---

## 13. Performance Summary

### By strategy (FnO 15m, OOS)
| Config | PF | Win% | Max DD | Net/trade |
|---|---|---|---|---|
| Fixed 1.61× + Fib trail | 14.80 | 83% | 1.93% | +0.81% |
| Slab trail (gross) | 5.59 | 49% | 7.38% | +1.10% |
| Slab trail + BE@15% | 9.67 | 24% | 7.30% | +0.71% |

### January 2026 simulation (30 stocks, slab trail)
- 181 signals, 51% win, +73.8% stock P&L
- Monthly option EV: +11.8% of premium per trade
- At ₹50K/trade: ~₹10 lakh/month

### March 2026 simulation (30 stocks, slab trail)
- 71 signals, 56% win, +60% stock P&L
- 38 trades reached slab levels, 3 hit TARGET (3.618×)
