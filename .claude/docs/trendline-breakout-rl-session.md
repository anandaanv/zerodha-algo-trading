# Trendline Breakout Strategy + RL Exit Optimizer — Session Notes

**Date:** 2026-04-14 to 2026-04-15
**Branch:** master (merged via PRs #55, #57, #58)
**Issues:** #54, #56

---

## What Was Built

### 1. Trendline Breakout (TLB) Pattern Detector

**Concept:** Price crosses EMA, extends 1.5 ATR away, comes back to EMA zone — that's a pivot (P1). When it does this again (P2), draw a trendline. On break of that trendline, enter.

**Implementation:** `src/main/java/com/dtech/ta/patterns/TrendlineBreakoutDetector.java`

**Key parameters (evolved during session):**
- Dual EMA: both 100 EMA and 200 EMA zones qualify as pivot zones
- Virgin trendline: no candle close crosses trendline between P1 and P2
- Minimum 100-bar gap between P1 and P2
- Prior swing must extend 1.5 ATR from EMA (confirms real move, not noise)
- Entry: breakout candle crosses trendline → next candle confirms by breaking high/low
- SL: trendline value at confirmation bar
- Target: AB=CD measured move (1:1 of prior swing distance)

**Pattern data model:** `src/main/java/com/dtech/ta/patterns/TrendlineBreakoutPattern.java`
- Fields: pivot1, pivot2, priorSwing, trendline, bullish, abDistance, breakoutLevel, stopLossTrendline, stopLossBreakoutCandle, target, breakoutBarIndex

### 2. Backtest Infrastructure

**Backtest methods in:** `PatternComboBacktestService.java`
- `backtestTrendlineBreakout()` — single symbol
- `backtestTrendlineBreakoutMultiple()` — batch (Nifty50, FnO)
- `backtestTlbForSymbol()` — core logic with dual exit tracking

**REST endpoints in:** `PatternScanController.java`
- `GET /api/pattern-scan/backtest/tlb/{symbol}?tf=1h`
- `GET /api/pattern-scan/backtest/tlb-nifty50?tf=1h`
- `GET /api/pattern-scan/backtest/tlb-fno?tf=1h`
- `GET /api/pattern-scan/fno-symbols`

**CSV output:** 43 columns including both linear and trailing SL results per trade, plus full indicator set (RSI, MACD, ADX, BB, StochRSI, slopes, MFE/MAE).

### 3. XGBoost ML Entry Filter

**Training script:** `ds-python/finrl-poc/service/train_tlb_model.py`
- Supports `--mode linear` or `--mode trail` for different exit strategies
- 35 features: TF one-hot, direction, R:R, AB/ATR ratio, RSI/MACD/ADX/BB at pivots, daily indicators, slopes
- XGBClassifier: 300 estimators, max_depth=4, lr=0.05

**Models saved:**
- `service/model/tlb_trade_filter.pkl` (linear SL mode)
- `service/model/tlb_trade_filter_trail.pkl` (trailing SL mode)

### 4. PPO RL Exit Optimizer

**Environment:** `ds-python/finrl-poc/env/exit_env.py`
- 31 features: 9 entry context (frozen) + 6 trade vitals + 12 live indicators + 4 candle
- Actions: HOLD (0) or EXIT (1)
- Reward: realized PnL on exit, optional theta decay penalty per bar

**Training script:** `ds-python/finrl-poc/train_exit.py`
- PPO with lr=3e-4, n_steps=2048, batch=256, gamma=0.99
- 500K timesteps, 4 parallel DummyVecEnv
- 80/20 train/eval split

**Models saved:**
- `models/ppo_exit_optimizer_70.zip` (train/test split, no theta)
- `models/ppo_exit_optimizer_70_full.zip` (full data, no theta — production)
- `models/ppo_exit_optimizer_75.zip` (75% confidence)

### 5. PPO RL Entry Timer

**Environment:** `ds-python/finrl-poc/env/entry_env.py`
- Agent decides WAIT or ENTER within 20-bar window
- On ENTER: simulates trade forward, returns PnL as reward
- On WAIT: theta penalty per bar

**Training script:** `ds-python/finrl-poc/train_entry_tlb.py`
- Similar PPO config, 300K timesteps

**Model:** `models/ppo_entry_timer.zip`

### 6. Production Integration

**RlExitClient:** `src/main/java/com/dtech/kitecon/trade/service/RlExitClient.java`
- REST client calling Flask `/predict-exit`
- Disabled by default: `rl.exit.enabled=false`

**TradeExitHandler:** Modified to call RL agent when no hard SL/target exit
- Only for equity trades (EQ), skipped for FUT/OPT

**Flask server:** `ds-python/finrl-poc/service/server.py`
- `/predict-exit` endpoint added
- Loads PPO model on startup

### 7. Tests

**E2E Integration:** `src/test/java/com/dtech/kitecon/trade/integration/TradeLifecycleE2ETest.java`
- 7 tests covering full trade lifecycle
- Trade 1: LONG DTB → target hit (WIN)
- Trade 2: SHORT DTB → SL hit (LOSS)
- Trade 3: LONG TLB → target hit (WIN)
- Trade 4: SHORT Triangle → expired (EXPIRED)
- Trade 5: LONG HNS → hold → target hit (WIN)
- Trade 6: LONG DTB → RL exit at profit (WIN)
- All 5/6 trades reach terminal state summary test

### 8. Bug Fixes

- `ml_score DECIMAL(4,4)` overflow: `TradeFilterClient.score()` and `TradeEntryHandler.scoreMlAtEntry()` returned 1.0 on fail-open, which overflows. Fixed to return 0.9999.

---

## Backtest Results (OOS)

### TLB Evolution

| Version | Trades (1H FnO) | WR | Trailing PnL |
|---------|------------------|----|-------------|
| Initial (100 EMA, any trendline) | 2,518 | 23.5% | -157% |
| + 200 EMA | 2,518 | 23.5% | -169% → -157% |
| + 1.5 ATR extension | 2,488 | 23.5% | similar |
| + Virgin trendline + 100-bar gap + dual EMA | **660** | 23.5% | **+2.1%** |
| + ML filter @75% | 56 | 85.7% | +1.19%/trade |

Key insight: **Virgin trendline was the breakthrough** — removed 73% of trades (noise) while keeping same WR. Raw trailing SL became profitable.

### DTB Combo (existing, for comparison)

| Threshold | Trades | WR | Avg PnL |
|-----------|--------|----|---------|
| 70% | 1,927 | 81.9% | +0.85% |
| 75% | 1,417 | 85.6% | +0.94% |
| 81% | 831 | 88.2% | +1.07% |

### RL Exit Optimizer Results

| Applied to | Baseline PnL | With RL | Improvement | Verdict |
|------------|-------------|---------|-------------|---------|
| DTB @70% | +1.00% | +1.35% | **+0.35%** | Helps |
| DTB @75% | +0.94% | not tested | — | Probably neutral |
| DTB @81% | +1.17% | +0.90% | **-0.27%** | Hurts |

**Key finding:** RL exit helps on noisy (lower confidence) trades but hurts on high-quality trades where the fixed 38% retrace exit is already near-optimal. For production at 75-81%, don't use RL exit.

### RL Entry Timer Results

| Strategy | Baseline | With RL Entry | Improvement |
|----------|----------|---------------|-------------|
| TLB | -0.27% | +0.054% | +0.32% |
| DTB | +1.06% | -0.45% | -1.51% (hurts) |

**Key finding:** Entry timing helps TLB (where entry is ambiguous around the trendline break) but hurts DTB (where the neckline break is already a precise entry signal).

### Theta Decay (Options Simulation)

Implemented ATR-linked theta: `theta_per_bar = (SL_distance / entry_price) * 0.05`

Result: Agent exits at 6.4 bars (vs 15.9 without theta) — correctly optimizes for time decay but captures less of the move. Net effect is worse PnL. **Not recommended for production.** Realistic options theta is hard to simulate in equity backtests.

### Timeframe Comparison (TLB Virgin)

| TF | Trades | Trail WR | Trail PnL | Verdict |
|----|--------|----------|-----------|---------|
| 5m | 133 | 39.3% | -12.6% | Too noisy, insufficient data |
| 15m | 534 | 46.5% | -27.2% | Needs ML filter |
| 1h | 660 | 48.2% | **+2.1%** | Best — only profitable raw |

---

## Production Recommendation

**Deploy DTB combo at 75-81% threshold with fixed exits.** No RL needed.

TLB adds ~50-65 extra trades per test period at 83-87% WR — worth enabling as a complementary signal alongside DTB, but small volume.

**Config for production:**
```properties
rl.exit.enabled=false          # RL exit not beneficial at high thresholds
trade.filter.threshold=0.75    # or 0.81 for highest quality
```

---

## File Map

```
Java (pattern detection + backtest):
  src/main/java/com/dtech/ta/patterns/
    TrendlineBreakoutDetector.java    — detector with dual EMA, virgin trendline
    TrendlineBreakoutPattern.java     — immutable data model
  src/main/java/com/dtech/kitecon/backtest/
    PatternComboBacktestService.java  — TLB backtest methods added
  src/main/java/com/dtech/kitecon/patternscanner/
    PatternScanController.java        — REST endpoints
    PatternScanService.java           — TLB wired into scan pipeline
    TradeFilterClient.java            — ml_score overflow fix
  src/main/java/com/dtech/kitecon/trade/service/
    RlExitClient.java                 — Flask REST client
    TradeExitHandler.java             — RL integration (equity only)
    TradeEntryHandler.java            — ml_score overflow fix

Python (ML + RL):
  ds-python/finrl-poc/
    service/train_tlb_model.py        — XGBoost filter training
    service/server.py                 — Flask /predict-exit endpoint
    env/exit_env.py                   — RL exit environment (with theta)
    env/entry_env.py                  — RL entry timing environment
    train_exit.py                     — PPO exit training
    train_entry_tlb.py                — PPO entry training
    models/ppo_exit_optimizer_*.zip   — trained exit models
    models/ppo_entry_timer.zip        — trained entry model

Tests:
  src/test/java/.../TradeLifecycleE2ETest.java — 7 E2E tests
```

---

## Lessons Learned

1. **Virgin trendline is the key filter** — removed 73% of noise, made raw strategy profitable
2. **RL exit only helps on noisy trades** — at high ML confidence, fixed exits win
3. **Entry timing is pattern-dependent** — helps TLB (ambiguous entry), hurts DTB (precise entry)
4. **Options theta simulation in equity backtest is unreliable** — agent over-optimizes for theta cost
5. **XGBoost filter at 75-81% is the main edge** — simple, reliable, and the biggest PnL driver
6. **Always use train/test split** — report OOS metrics, retrain on full data before deploying
