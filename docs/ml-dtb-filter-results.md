# DTB retest filter — ML training results

**Date:** 2026-05-11
**Branch:** feature/chart-pattern-eval
**Pipeline:** `src/test/java/com/dtech/ta/patterns/classic/ExtractDtbTrainingDataTest.java` + `ds-python/finrl-poc/service/train_hns_filter.py` (reused, label-agnostic)

## Headline

**DTB ML filter has higher peak AUC than HNS (0.7725 vs 0.7244) but suffers severe class imbalance at the tight-label setting.** Practical deployment point is pnl≥1.5% label at threshold 0.55 (89 test trades, 40% win, +0.31%/trade). Strong unfiltered baseline (65.2% win rate) limits the value-add of the filter.

## Pipeline

- Detector: `DoubleTopClassicDetector` + `DoubleBottomClassicDetector` with sliding-window scan (window=200, slide=100, de-dup by symbol+type+endBarIndex).
- Simulation: enter on retest of neckline within ±0.5% after breakout (close-basis), 30-bar lookahead each for breakout and retest, 30-bar time stop, head-of-pattern SL.
- Features: 395-feature `ImpulseFeatureExtractor` captured at entry bar.
- Universe: 253 FNO stocks (same as HNS run).
- Dataset: 1,596 trades, chronological 70/30 split (1,117 train / 479 test).

## Diagnostic counters (from extraction)

```
detected   = 2,824 patterns
noBreakout = 1,011  (neckline never closed beyond within 30 bars)
noRetest   =    92  (broke out but no retest in ±0.5% within 30 bars)
lateDetect =    83  (pattern too close to series end)
badGeom    =    42  (entry/SL/TP geometry rejected)
entered    = 1,596  ✅
```

Baseline (no ML filter): 1,042 wins / 554 losses = **65.2% win rate**, total PnL +9.06% summed.

## Label sweep × threshold sweep

```
Label = pnl ≥ 0% (any positive)
  AUC: 0.5447 | accuracy: 59% | base rate: 65% winners
    0.50: 332 trades, 66.9% win, +42.56% total, +0.13%/trade
    0.55: 287 trades, 66.6% win, +29.20% total, +0.10%/trade
    0.60: 237 trades, 66.7% win, +17.26% total, +0.07%/trade
    0.65: 183 trades, 66.7% win, +12.18% total, +0.07%/trade
    0.70: 137 trades, 67.2% win,  +5.78% total, +0.04%/trade

Label = pnl ≥ 1.5%
  AUC: 0.6348 | accuracy: 67% | base rate: 30% winners
    0.50: 115 trades, 40.0% win, +23.80% total, +0.21%/trade
    0.55:  89 trades, 40.4% win, +27.44% total, +0.31%/trade  <- best volume×PnL
    0.60:  66 trades, 43.9% win,  +9.62% total, +0.15%/trade
    0.65:  42 trades, 42.9% win, +11.01% total, +0.26%/trade
    0.70:  24 trades, 50.0% win,  +8.12% total, +0.34%/trade

Label = pnl ≥ 2.0%
  AUC: 0.6592 | accuracy: 80% | base rate: 17% winners
  ⚠ All thresholds produce NEGATIVE mean PnL — model inverted on test set.
    0.50:  45 trades, 20.0% win, −16.43% total, −0.37%/trade
    0.55:  36 trades, 16.7% win, −12.85% total, −0.36%/trade
    0.60:  28 trades, 17.9% win, −18.05% total, −0.64%/trade

Label = pnl ≥ 3.0%
  AUC: 0.7725 | accuracy: 95% | base rate:  5% winners (22 in 479 test)
  Severe imbalance; threshold sweep too small for confidence.
    0.50:   7 trades, 28.6% win,  +8.48% total, +1.21%/trade
    0.55:   5 trades, 40.0% win, +13.90% total, +2.78%/trade
    0.60:   3 trades, 66.7% win,  +9.82% total, +3.27%/trade
    0.70:   2 trades, 50.0% win,  +6.41% total, +3.21%/trade
```

## Side-by-side: HNS vs DTB

| Variant | HNS AUC | DTB AUC | HNS best mean/trade | DTB best mean/trade |
|---|---|---|---|---|
| pnl≥0%   | 0.5101 | 0.5447 | +0.49% (109 tr) | +0.13% (332 tr) |
| pnl≥1.5% | 0.5593 | 0.6348 | +0.74% ( 84 tr) | +0.31% ( 89 tr) |
| pnl≥2.0% | 0.6517 | 0.6592 | +0.85% ( 57 tr) | NEGATIVE (broken) |
| pnl≥3.0% | 0.7244 | 0.7725 | +0.65% ( 59 tr) | +3.27% (  3 tr — too few) |

- DTB has stronger raw signal (higher AUC across the board) but smaller effective sample at tight labels because the DTB win-rate baseline is already 65.2% vs HNS lower.
- HNS produces statistically reliable operating points at pnl≥2.0% / threshold 0.70.
- DTB pnl≥2.0% appears to overfit or has regime shift between train and test windows — needs walk-forward retrain before any production use.

## Recommended operating point for DTB

| Goal | Variant | Threshold | Trade volume (test) | Mean P/L |
|---|---|---|---|---|
| Higher-conviction subset | pnl≥1.5% model | 0.55 | 89 → ~300 full | +0.31% |
| Accept baseline | none (no ML) | — | 1,596 over 16mo | +0.006% (raw, but 65% win rate) |

The baseline DTB unfiltered is already 65% win rate — close to ML-filtered HNS performance. **DTB filtering yields marginal lift; main value is reducing trade count to a manageable level for review, not improving win rate.**

## Top 20 features (pnl≥3.0% model)

```
f205, f060, f098, f181, f084, f019, f165, f083, f149, f175,
f251, f203, f130, f216, f176, f146, f317, f236, f125, f158
```

Different signature than HNS (HNS top: f037, f127, f198, f130, f246, ...) — only f130 overlaps. Suggests DTB and HNS are complementary in feature usage.

## Caveats

1. **Imbalance penalty for tight labels**: at pnl≥3.0% only 22 winners in test → confusion-matrix metrics unstable. Need 2-3× more data before trusting AUC at this label.
2. **Single-regime test data** (Oct 2024 – May 2026, continuous bull market). Walk-forward across 2018-2023 needed before live.
3. **DTB baseline is good (65.2% win)** — filtering trades off this baseline costs volume more than it gains lift. ML filter is a "trade discriminator", not a "trade improver" here.
4. **pnl≥2.0% inversion**: the model performs *worse* than random on this label.
   - Train/test pnl distribution is stable (18.2% vs 14.6% in the ≥2% bucket — small shift, not regime).
   - Adding direction (LONG/SHORT) as a feature did NOT help (AUC 0.6592 → 0.6602, sweep still negative).
   - Conclusion: not a labelling or feature omission bug. The model finds high-conviction signals that happen to be high-volatility — when those lose, they lose big enough to drag the mean negative. Skip this label setting for DTB; pnl≥1.5% is the usable tight-label point.

## Combined HNS + DTB ensemble (suggested next step)

Given top-feature divergence and complementary AUCs, a stacked HNS + DTB classifier may outperform either alone. Worth testing:
1. Train a meta-model on (HNS_prob, DTB_prob, pattern_type) → final probability.
2. Backtest combined trades across both pattern families with a single threshold.

## Reproducing

```bash
# Extract (~6 min, 253 stocks)
./gradlew test --tests com.dtech.ta.patterns.classic.ExtractDtbTrainingDataTest

# Train with label sweep
/tmp/run_dtb_training_variants.sh 2>&1 | tee /tmp/dtb_training_output.log
```
