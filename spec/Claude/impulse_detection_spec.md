# Impulse Detection & Feature Extraction System — Specification

## 1. Purpose

Build a system that scans historical zigzag data, identifies impulse waves (wave 3 and wave 5 starts), labels them, and extracts a full feature snapshot at each labeled pivot. The output is a structured training dataset for a LightGBM classifier that predicts impulse probability at any given pivot.

---

## 2. Input Data Requirements

### 2.1 Zigzag Data (Primary Input)

The system receives pre-computed zigzag data. The zigzag logic is external, ATR-based, and already validated. Each zigzag pivot record must contain:

- `timestamp` — datetime of the pivot
- `price` — price at the pivot
- `direction` — whether this pivot is a HIGH or LOW
- `leg_size_pct` — percentage move from the prior pivot to this one
- `leg_duration_bars` — number of bars from the prior pivot to this one
- `retracement_pct` — this leg's size as a percentage of the prior leg (how much of the prior leg it retraced)

### 2.2 Market Trend Data

At each zigzag pivot:

- `trend_state` — one of: HH (higher high), HL (higher low), LH (lower high), LL (lower low)
- `trend_streak` — how many consecutive pivots have maintained the current directional bias
- `bars_since_trend_change` — bars since the last trend state reversal

### 2.3 Indicator Data

At each zigzag pivot, the following indicators must be available. All values are the indicator reading at the exact bar of the zigzag pivot.

| Indicator         | Fields                                      |
| ----------------- | ------------------------------------------- |
| RSI (14)          | `rsi`                                       |
| Stochastic (14,3) | `stoch_k`, `stoch_d`, `stoch_crossover` (boolean: did K cross D between this pivot and the prior one) |
| MACD (12,26,9)    | `macd_line`, `macd_signal`, `macd_histogram` |
| ADX (14)          | `adx`, `plus_di`, `minus_di`                |
| EMA 20            | `ema20_dist_pct` (price distance from EMA as percentage) |
| EMA 50            | `ema50_dist_pct`                            |
| EMA 200           | `ema200_dist_pct`                           |
| Bollinger (20,2)  | `bb_position` (0-1 scale, where price sits within bands), `bb_bandwidth` |

Total: 15 indicator fields per pivot per timeframe.

### 2.4 Multi-Timeframe Data

The system operates on a primary timeframe and requires data from:

- **Current TF** — the primary analysis timeframe (e.g., daily)
- **Higher TF** — one level up (e.g., weekly if current is daily)
- **Lower TF** — one level down (e.g., 4H if current is daily)

The exact timeframe triplet is configurable. The user will provide it at runtime.

---

## 3. Labeling Rules

### 3.1 Wave 3 Start Identification

Scan zigzag pivots sequentially. A pivot P is labeled as **wave 3 start** if ALL of the following conditions are met:

**Looking backward from P (these are known at pivot P):**

1. The leg ending at P (wave 2 candidate) retraced **50% to 61.8%** of the leg before it (wave 1 candidate).
2. The wave 1 candidate was a directional leg (not part of a sideways chop — its retracement of the prior leg should be > 100%, meaning it exceeded the prior move).

**Looking forward from P (used only for historical labeling, not real-time):**

3. The leg starting from P (wave 3 candidate) is at least **1.618x the size** of wave 1 in percentage terms.
4. The wave 3 candidate never retraces more than **23.6%** internally at any point during its development. (This requires checking sub-pivots or bar-level data within the wave 3 leg.)
5. After the wave 3 candidate completes, a correction follows that retraces **23.6% to 50%** of wave 3 (wave 4 confirmation).

**Label:** `wave3_start`
**Direction:** inherited from the wave 3 leg direction (up = bullish impulse, down = bearish impulse)

### 3.2 Wave 5 Start Identification

A pivot Q is labeled as **wave 5 start** if ALL of the following conditions are met:

**Looking backward from Q:**

1. There is a confirmed wave 3 (already labeled) preceding Q.
2. The leg ending at Q (wave 4 candidate) retraced **23.6% to 50%** of the wave 3 leg.
3. Wave 4 does NOT overlap with wave 1 territory (price at Q does not breach the end of wave 1). This is a core EW rule.

**Looking forward from Q (used only for historical labeling):**

4. The leg starting from Q (wave 5 candidate) is at least **0.618x the size** of wave 3 in percentage terms.
5. The wave 5 candidate moves in the same direction as wave 3.

**Label:** `wave5_start`
**Direction:** same as the associated wave 3 direction.

### 3.3 Negative Labeling

Every zigzag pivot that does NOT qualify as a wave 3 start or wave 5 start receives the label `no_impulse`. These are the negative training examples.

To ensure useful negatives, include pivots that partially matched but failed — for example:

- Pivots where the prior retracement was in the 50-61.8% zone but the following leg did not reach 1.618x wave 1 (failed wave 3)
- Pivots where wave 4 formed correctly but wave 5 was truncated (failed wave 5)

These "near miss" negatives are the most valuable for training.

### 3.4 Labeling Summary

| Label         | Meaning                                 | Used for                     |
| ------------- | --------------------------------------- | ---------------------------- |
| `wave3_start` | Pivot at the start of a confirmed wave 3 | Positive class (impulse)     |
| `wave5_start` | Pivot at the start of a confirmed wave 5 | Positive class (impulse)     |
| `no_impulse`  | Pivot that did not precede an impulse   | Negative class               |

The model can be trained as a 3-class classifier (wave3 / wave5 / neither) or binary (impulse / no_impulse) depending on experimentation.

---

## 4. Feature Extraction

At every labeled pivot, extract the following features. All features use ONLY data available at or before the pivot timestamp (no forward-looking data in features — forward data is used only for labeling).

### 4.1 Group A — Current Pivot Properties (8 features)

| Feature Name          | Description                                                       |
| --------------------- | ----------------------------------------------------------------- |
| `curr_direction`      | Pivot direction: 1 = HIGH, -1 = LOW                              |
| `curr_retrace_pct`    | Retracement % of the leg ending at this pivot                     |
| `curr_leg_size_pct`   | Size of the leg ending at this pivot (%)                          |
| `curr_leg_duration`   | Duration of the leg ending at this pivot (bars)                   |
| `curr_leg_speed`      | `curr_leg_size_pct / curr_leg_duration`                           |
| `curr_ema20_dist`     | Price distance from EMA 20 at this pivot (%)                      |
| `curr_ema50_dist`     | Price distance from EMA 50 (%)                                    |
| `curr_ema200_dist`    | Price distance from EMA 200 (%)                                   |

### 4.2 Group B — Last 8 Pivots: Full Data (160 features)

For each of the 8 most recent pivots before the current one (n1 = most recent, n8 = oldest), extract:

**Structural (5 per pivot):**

| Feature Name                | Description                                  |
| --------------------------- | -------------------------------------------- |
| `pN_leg_size_pct`           | Leg size as percentage                       |
| `pN_leg_duration`           | Leg duration in bars                         |
| `pN_retrace_pct`            | Retracement of prior leg (%)                 |
| `pN_leg_speed`              | Leg size / duration                          |
| `pN_trend_state`            | HH=1, HL=2, LH=3, LL=4                      |

**Indicators (15 per pivot):**

| Feature Name                | Description                                  |
| --------------------------- | -------------------------------------------- |
| `pN_rsi`                    | RSI value at pivot                           |
| `pN_stoch_k`                | Stochastic %K                                |
| `pN_stoch_d`                | Stochastic %D                                |
| `pN_stoch_cross`            | Stochastic crossover between this and prior pivot (0/1) |
| `pN_macd_line`              | MACD line value                              |
| `pN_macd_signal`            | MACD signal value                            |
| `pN_macd_hist`              | MACD histogram value                         |
| `pN_adx`                    | ADX value                                    |
| `pN_plus_di`                | +DI value                                    |
| `pN_minus_di`               | -DI value                                    |
| `pN_ema20_dist`             | Price distance from EMA 20 (%)               |
| `pN_ema50_dist`             | Price distance from EMA 50 (%)               |
| `pN_ema200_dist`            | Price distance from EMA 200 (%)              |
| `pN_bb_position`            | Position within Bollinger bands (0-1)        |
| `pN_bb_bandwidth`           | Bollinger bandwidth                          |

Where N = 1 to 8. Total: 20 features × 8 pivots = **160 features**.

### 4.3 Group C — Pivots 9-20: Structural Only (60 features)

For pivots 9 through 20 (older history, for pattern shape detection):

| Feature Name                | Description                                  |
| --------------------------- | -------------------------------------------- |
| `pN_leg_size_pct`           | Leg size as percentage                       |
| `pN_leg_duration`           | Leg duration in bars                         |
| `pN_retrace_pct`            | Retracement of prior leg (%)                 |
| `pN_leg_speed`              | Leg size / duration                          |
| `pN_trend_state`            | HH=1, HL=2, LH=3, LL=4                      |

Where N = 9 to 20. Total: 5 features × 12 pivots = **60 features**.

### 4.4 Group D — Market Trend Context (4 features)

| Feature Name                | Description                                          |
| --------------------------- | ---------------------------------------------------- |
| `mkt_trend_state`           | Current trend: HH=1, HL=2, LH=3, LL=4               |
| `mkt_trend_streak`          | Consecutive pivots in current trend direction         |
| `mkt_bars_since_reversal`   | Bars since last trend state change                   |
| `mkt_last_break_type`       | Last structural break: 1=continuation, -1=reversal   |

### 4.5 Group E — Higher Timeframe Context (16 features)

All indicator values are from the higher timeframe, evaluated at the bar corresponding to the current pivot's timestamp.

| Feature Name                | Description                                  |
| --------------------------- | -------------------------------------------- |
| `htf_trend_state`           | Higher TF trend: HH=1, HL=2, LH=3, LL=4    |
| `htf_retrace_pct`           | Retracement of current higher TF leg (%)     |
| `htf_pivot_dist_pct`        | Distance from last higher TF pivot (%)       |
| `htf_rsi`                   | RSI on higher TF                             |
| `htf_stoch_k`               | Stochastic %K                                |
| `htf_stoch_d`               | Stochastic %D                                |
| `htf_macd_line`             | MACD line                                    |
| `htf_macd_signal`           | MACD signal                                  |
| `htf_macd_hist`             | MACD histogram                               |
| `htf_adx`                   | ADX                                          |
| `htf_plus_di`               | +DI                                          |
| `htf_minus_di`              | -DI                                          |
| `htf_ema20_dist`            | Price distance from EMA 20 (%)               |
| `htf_ema50_dist`            | Price distance from EMA 50 (%)               |
| `htf_bb_position`           | Bollinger position                           |
| `htf_bb_bandwidth`          | Bollinger bandwidth                          |

### 4.6 Group F — Lower Timeframe Context (16 features)

| Feature Name                | Description                                  |
| --------------------------- | -------------------------------------------- |
| `ltf_trend_state`           | Lower TF trend: HH=1, HL=2, LH=3, LL=4     |
| `ltf_zigzag_count`          | Number of lower TF zigzag pivots within the current TF leg |
| `ltf_trend_reversal_flag`   | Did a lower TF trend reversal happen in last N bars (0/1) |
| `ltf_rsi`                   | RSI on lower TF                              |
| `ltf_stoch_k`               | Stochastic %K                                |
| `ltf_stoch_d`               | Stochastic %D                                |
| `ltf_macd_line`             | MACD line                                    |
| `ltf_macd_signal`           | MACD signal                                  |
| `ltf_macd_hist`             | MACD histogram                               |
| `ltf_adx`                   | ADX                                          |
| `ltf_plus_di`               | +DI                                          |
| `ltf_minus_di`              | -DI                                          |
| `ltf_ema20_dist`            | Price distance from EMA 20 (%)               |
| `ltf_ema50_dist`            | Price distance from EMA 50 (%)               |
| `ltf_bb_position`           | Bollinger position                           |
| `ltf_bb_bandwidth`          | Bollinger bandwidth                          |

### 4.7 Group G — Derived Structural Metrics (7 features)

Computed from the zigzag sequence, no indicator bias:

| Feature Name                | Description                                                     |
| --------------------------- | --------------------------------------------------------------- |
| `leg_size_vs_avg`           | Current leg size / average of last 10 legs                      |
| `leg_duration_vs_avg`       | Current leg duration / average of last 10 durations             |
| `bars_since_large_leg`      | Bars since last leg that was > 2x average size                  |
| `max_retrace_last10`        | Deepest retracement in last 10 legs (%)                         |
| `min_retrace_last10`        | Shallowest retracement in last 10 legs (%)                      |
| `leg_size_stddev`           | Standard deviation of last 10 leg sizes (captures chop vs trend)|
| `legs_contracting`          | Are last 5 legs progressively smaller? Ratio of avg(last 3) / avg(prior 3) |

---

## 5. Total Feature Count

| Group   | Description                     | Count |
| ------- | ------------------------------- | ----- |
| A       | Current pivot properties        | 8     |
| B       | Last 8 pivots (full)            | 160   |
| C       | Pivots 9-20 (structural only)   | 60    |
| D       | Market trend context            | 4     |
| E       | Higher TF context               | 16    |
| F       | Lower TF context                | 16    |
| G       | Derived structural metrics      | 7     |
| **Total** |                               | **271** |

---

## 6. Output Format

### 6.1 Training Dataset (CSV)

One CSV file where:

- Each row = one zigzag pivot
- Column 1: `timestamp`
- Column 2: `price`
- Column 3: `label` — one of `wave3_start`, `wave5_start`, `no_impulse`
- Column 4: `direction` — 1 (bullish) or -1 (bearish)
- Columns 5-275: all 271 features as defined above

### 6.2 Verification Report (text/JSON)

For sanity-checking the labeling, emit a summary:

```
SCAN SUMMARY
============
Stock: [name]
Timeframe: [primary TF]
Period: [start date] to [end date]
Total pivots scanned: [N]

Labels:
  wave3_start: [count]
  wave5_start: [count]
  no_impulse:  [count]

WAVE 3 STARTS
-------------
#1  Date: YYYY-MM-DD | Price: XXXX | Direction: LONG
    Wave 1: XXXX → XXXX (+X.X%)
    Wave 2 retrace: XX.X%
    Wave 3 move: +XX.X% (X.Xx of wave 1)
    Wave 3 max internal pullback: XX.X%
    Wave 4 retrace of wave 3: XX.X%

#2  Date: YYYY-MM-DD | Price: XXXX | Direction: SHORT
    ...

WAVE 5 STARTS
--------------
#1  Date: YYYY-MM-DD | Price: XXXX | Direction: LONG
    Associated Wave 3 date: YYYY-MM-DD
    Wave 4 retrace of wave 3: XX.X%
    Wave 5 move: +XX.X% (X.Xx of wave 3)

#2  ...
```

---

## 7. Implementation Notes

### 7.1 Handling Edge Cases

- If fewer than 20 prior pivots exist (start of data), pad missing pivot features with `NaN`. LightGBM handles NaN natively.
- If higher or lower TF data is not available at a pivot timestamp, pad with `NaN`.
- A single pivot can only have ONE label. If a pivot qualifies as both wave 3 start and wave 5 start (theoretically possible across different wave degrees), label it with the higher-confidence match based on stricter rule adherence.

### 7.2 Internal Retracement Check

The "wave 3 never retraces more than 23.6% internally" rule requires either:

- Access to bar-level OHLC data within the wave 3 leg to check if price ever pulled back >23.6% from the running extreme, OR
- Access to lower-timeframe zigzag pivots within the wave 3 leg and checking if any sub-pivot created a retracement > 23.6%.

The lower-TF zigzag approach is preferred since the data is already available.

### 7.3 No Forward-Looking Features

All 271 features must be computable using ONLY data at or before the labeled pivot's timestamp. Forward data is used exclusively for labeling (confirming that wave 3 or wave 5 actually played out). This is critical for preventing data leakage.

### 7.4 Feature Naming Convention

All features follow: `[group_prefix]_[pivot_index_if_applicable]_[indicator_name]`

Examples:
- `p3_rsi` — RSI at the 3rd most recent pivot
- `htf_adx` — ADX on the higher timeframe
- `curr_retrace_pct` — retracement at the current pivot
- `leg_size_vs_avg` — derived structural metric

### 7.5 Configurable Parameters

The following should be configurable at runtime, not hardcoded:

| Parameter                   | Default | Description                                    |
| --------------------------- | ------- | ---------------------------------------------- |
| `wave2_retrace_min`         | 50.0    | Minimum wave 2 retracement (%)                 |
| `wave2_retrace_max`         | 61.8    | Maximum wave 2 retracement (%)                 |
| `wave3_min_ratio`           | 1.618   | Minimum wave 3 size as multiple of wave 1      |
| `wave3_max_internal_retrace`| 23.6    | Maximum internal pullback within wave 3 (%)    |
| `wave4_retrace_min`         | 23.6    | Minimum wave 4 retracement of wave 3 (%)       |
| `wave4_retrace_max`         | 50.0    | Maximum wave 4 retracement of wave 3 (%)       |
| `wave5_min_ratio`           | 0.618   | Minimum wave 5 size as multiple of wave 3      |
| `full_indicator_pivots`     | 8       | Number of recent pivots with full indicator data|
| `structural_only_pivots`    | 12      | Number of older pivots with structural data only|

---

## 8. Validation Checklist

Before the output dataset is used for training:

- [ ] Manually verify at least 10 wave 3 labels on charts — do they look like impulse starts?
- [ ] Manually verify at least 5 wave 5 labels.
- [ ] Confirm no feature uses future data (spot-check by comparing feature timestamps to pivot timestamps).
- [ ] Check class distribution — if wave3/wave5 labels are < 5% of total pivots, the labeling rules may be too strict. If > 30%, too loose.
- [ ] Verify that the wave 3 to wave 5 linkage is consistent — every wave 5 should reference a prior wave 3.
- [ ] Check for NaN density — pivots at the start of data will have many NaNs, consider trimming the first 20 pivots.
