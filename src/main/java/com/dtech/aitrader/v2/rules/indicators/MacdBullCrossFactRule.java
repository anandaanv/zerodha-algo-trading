package com.dtech.aitrader.v2.rules.indicators;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-1 structural FACT: emits a {@link FiresOn#FACT} firing iff a MACD bullish cross occurred
 * within the most recent {@code K} bars (configurable; default 5). Reports the most recent cross
 * if multiple occurred in the window.
 *
 * <p>Per owner Q2 (ratification {@code 7885ad63}): "real confluence is MACD crossed a few bars
 * BEFORE the neckline broke". Same-bar coincidence is unrealistic and produced ZERO confluence
 * firings on the Path-A smoke run. Widening to K bars lets the Pass-4 classification rule pair
 * MACD facts with breakout-day pattern candidates that happen 1-K bars later.
 *
 * <p>K is sweepable via {@code rules.indicators.macd.cross-lookback-bars} so eval can later
 * decide whether K=3/5/7 yields genuine hit-rate lift (owner's load-bearing question — cross-family
 * confluence is the entire bet of the global-pass topology).
 */
@Component
@Slf4j
public class MacdBullCrossFactRule implements Rule {

    public static final String RULE_ID = "MACD_BULL_CROSS_FACT";

    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;

    @Value("${rules.indicators.macd.cross-lookback-bars:5}")
    private int crossLookbackBars;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P1_STRUCTURAL; }
    @Override public Family family() { return Family.INDICATOR; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        BarSeries series = ctx.getSeries();
        if (series == null) return List.of();
        int endIdx = series.getEndIndex();
        int warmup = MACD_SLOW + MACD_SIGNAL;
        if (endIdx < warmup) return List.of();

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(close, MACD_FAST, MACD_SLOW);
        EMAIndicator macdSignal = new EMAIndicator(macd, MACD_SIGNAL);

        // Scan back up to crossLookbackBars looking for the most recent bull-cross. We walk
        // FORWARD from oldest-in-window to newest so the *most recent* cross overwrites earlier
        // matches (records the freshest).
        int crossIdx = -1;
        int startScan = Math.max(warmup, endIdx - crossLookbackBars + 1);
        for (int i = startScan; i <= endIdx; i++) {
            if (i < 1) continue;
            double mNow = macd.getValue(i).doubleValue();
            double sNow = macdSignal.getValue(i).doubleValue();
            double mPrev = macd.getValue(i - 1).doubleValue();
            double sPrev = macdSignal.getValue(i - 1).doubleValue();
            if (mPrev <= sPrev && mNow > sNow) crossIdx = i;
        }
        if (crossIdx < 0) return List.of();

        int barsFromCross = endIdx - crossIdx;
        double mNow = macd.getValue(crossIdx).doubleValue();
        double sNow = macdSignal.getValue(crossIdx).doubleValue();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("macd_line_at_cross", mNow);
        payload.put("macd_signal_at_cross", sNow);
        payload.put("macd_histogram_at_cross", mNow - sNow);
        payload.put("cross_bar_idx", crossIdx);
        payload.put("bars_from_cross", barsFromCross);
        payload.put("lookback_k", crossLookbackBars);

        return List.of(Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.INDICATOR)
                .pass(Pass.P1_STRUCTURAL)
                .firesOn(FiresOn.FACT)
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build());
    }
}
