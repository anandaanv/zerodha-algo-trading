package com.dtech.aitrader.v2.rules;

import com.dtech.aitrader.data.FiringOutcome;
import com.dtech.aitrader.data.RuleFiring;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks the next N bars after a {@link RuleFiring}'s as-of and resolves the outcome.
 *
 * <p>Outcome rules per spec {@code f23b95be}:
 * <ul>
 *   <li>LONG: HIT if {@code high ≥ trigger + 0.95 × (target - trigger)} BEFORE
 *       {@code low ≤ invalidation}. Else INVALIDATED if {@code low ≤ invalidation}.
 *       Else PENDING.</li>
 *   <li>SHORT: mirror.</li>
 * </ul>
 *
 * <p>MFE / MAE are tracked as percentages from {@code trigger} across the entire window — even
 * when the outcome resolves early, the scorer keeps walking to capture max-favourable / max-adverse
 * for richer eval. Bars-to-target / bars-to-invalidation count from the as-of (1 = the bar right
 * after as-of).
 *
 * <p>The scorer reads bars via {@link ChartDataService#getBars} — NOT via {@link ContextLoader} —
 * because it is allowed to see future bars (the leakage guard applies to rules, not to forward
 * outcome scoring).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WalkForwardOutcomeScorer {

    public static final int DEFAULT_WINDOW_BARS = 20;

    private final ChartDataService chartDataService;

    /** Convenience overload — uses {@link #DEFAULT_WINDOW_BARS}. */
    public FiringOutcome score(RuleFiring firing) {
        return score(firing, DEFAULT_WINDOW_BARS);
    }

    /**
     * Score a single firing over the next {@code windowBars} trading bars. Returns
     * {@code null} if the firing references a symbol/TF that has no bars in the DB.
     */
    public FiringOutcome score(RuleFiring firing, int windowBars) {
        List<OhlcBarDTO> allBars;
        try {
            allBars = chartDataService.getBars(firing.getSymbol(), firing.getTf(), null, null, false);
        } catch (Exception e) {
            log.warn("[outcome] {} {} fetch failed: {}", firing.getSymbol(), firing.getTf(),
                    e.getMessage());
            return null;
        }
        if (allBars == null || allBars.isEmpty()) return null;

        long asOfEpoch = firing.getAsOf().atTime(23, 59, 59)
                .atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond();

        // Take bars strictly AFTER as-of (the firing bar itself is excluded — outcome window starts
        // on the next bar). Cap to windowBars.
        List<OhlcBarDTO> forward = new ArrayList<>();
        for (OhlcBarDTO b : allBars) {
            if (b.getTime() > asOfEpoch) {
                forward.add(b);
                if (forward.size() >= windowBars) break;
            }
        }
        if (forward.isEmpty()) {
            return FiringOutcome.builder()
                    .firingId(firing.getId())
                    .outcome(FiringOutcome.Outcome.PENDING)
                    .mfePct(0.0)
                    .maePct(0.0)
                    .windowBars(0)
                    .build();
        }

        boolean isLong = firing.getBias() == RuleFiring.Bias.LONG;
        double trigger = firing.getTriggerPrice();
        double invalidation = firing.getInvalidationPrice();
        Double targetBoxed = firing.getTargetPrice();
        double target = targetBoxed == null
                ? (isLong ? trigger + 2 * Math.abs(trigger - invalidation)
                          : trigger - 2 * Math.abs(trigger - invalidation))
                : targetBoxed;

        // 95% of the move counts as HIT — covers slippage on the final bar.
        double hitLevel = isLong
                ? trigger + 0.95 * (target - trigger)
                : trigger - 0.95 * (trigger - target);

        double mfe = 0.0;  // max favourable % from trigger
        double mae = 0.0;  // max adverse %    from trigger
        FiringOutcome.Outcome outcome = FiringOutcome.Outcome.PENDING;
        Integer barsToTarget = null;
        Integer barsToInvalidation = null;
        LocalDate outcomeDate = null;

        for (int i = 0; i < forward.size(); i++) {
            OhlcBarDTO b = forward.get(i);
            double favEdge = isLong ? b.getHigh() : b.getLow();
            double advEdge = isLong ? b.getLow() : b.getHigh();

            // Update running MFE / MAE.
            double favPct = isLong ? pctChange(trigger, favEdge) : pctChange(favEdge, trigger);
            double advPct = isLong ? pctChange(advEdge, trigger) : pctChange(trigger, advEdge);
            if (favPct > mfe) mfe = favPct;
            if (advPct > mae) mae = advPct;

            // First-touch resolution (don't overwrite if already resolved).
            if (outcome == FiringOutcome.Outcome.PENDING) {
                boolean hitNow = isLong ? favEdge >= hitLevel : favEdge <= hitLevel;
                boolean invNow = isLong ? advEdge <= invalidation : advEdge >= invalidation;
                if (hitNow && invNow) {
                    // Both touched on same bar — pessimistic resolution: invalidation wins.
                    outcome = FiringOutcome.Outcome.INVALIDATED;
                    barsToInvalidation = i + 1;
                    outcomeDate = LocalDate.ofInstant(Instant.ofEpochSecond(b.getTime()),
                            ZoneId.of("Asia/Kolkata"));
                } else if (hitNow) {
                    outcome = FiringOutcome.Outcome.HIT;
                    barsToTarget = i + 1;
                    outcomeDate = LocalDate.ofInstant(Instant.ofEpochSecond(b.getTime()),
                            ZoneId.of("Asia/Kolkata"));
                } else if (invNow) {
                    outcome = FiringOutcome.Outcome.INVALIDATED;
                    barsToInvalidation = i + 1;
                    outcomeDate = LocalDate.ofInstant(Instant.ofEpochSecond(b.getTime()),
                            ZoneId.of("Asia/Kolkata"));
                }
            }
        }

        // If still PENDING at end of window AND the full window was available, it's a MISS — the
        // setup neither hit target nor blew through invalidation. Distinguished from a true PENDING
        // where the window is short because we don't yet have enough forward data.
        if (outcome == FiringOutcome.Outcome.PENDING && forward.size() >= windowBars) {
            outcome = FiringOutcome.Outcome.MISS;
        }

        return FiringOutcome.builder()
                .firingId(firing.getId())
                .outcome(outcome)
                .outcomeBarDate(outcomeDate)
                .mfePct(mfe)
                .maePct(mae)
                .barsToTarget(barsToTarget)
                .barsToInvalidation(barsToInvalidation)
                .windowBars(forward.size())
                .build();
    }

    private static double pctChange(double from, double to) {
        if (from == 0) return 0;
        return ((to - from) / Math.abs(from)) * 100.0;
    }
}
