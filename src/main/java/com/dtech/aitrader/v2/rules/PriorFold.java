package com.dtech.aitrader.v2.rules;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure folder that turns a candidate's chain of {@link PriorDelta}s into a final
 * {@code (live_prior, eliminated)} pair. Per the multi-pass engine spec ({@code 4e185036}): the
 * fold is the only mechanism that derives a candidate's prior — firings themselves are immutable.
 *
 * <p>Callers ({@code MultiPassEngine}) MUST pre-sort the list by {@code (round, pass, firing_id)}
 * before calling fold — this class does not re-sort. Per Q4 of the convergence memo, current
 * deltas are commutative under clamp, but the engine still folds in stable order for future-proof
 * determinism.
 *
 * <p>Test coverage: see {@code PriorFoldTest} for the locked-in behaviours.
 */
public final class PriorFold {

    private PriorFold() {}

    /**
     * Walk the chain, applying each delta against a running {@code (prior, eliminated)} state.
     *
     * <p>Once {@code eliminated=true}, no later delta modifies the prior — they remain in the
     * audit trail but are inert at fold time (Q5 sticky-elimination).
     */
    public static Result fold(double basePrior, List<PriorDelta> chainInOrder) {
        double prior = clamp01(basePrior);
        boolean eliminated = false;

        for (PriorDelta d : chainInOrder) {
            if (eliminated) continue;
            switch (d.kind()) {
                case CATEGORICAL_ELIMINATE -> {
                    eliminated = true;
                    prior = 0.0;
                }
                case GRADUATED -> {
                    Double delta = d.graduatedDelta();
                    if (delta != null) prior = clamp01(prior + delta);
                }
                case FLOOR_SET -> {
                    Double floor = d.floorValue();
                    if (floor != null) prior = Math.max(prior, clamp01(floor));
                }
            }
        }
        return new Result(prior, eliminated);
    }

    private static double clamp01(double x) {
        if (x < 0) return 0;
        if (x > 1) return 1;
        return x;
    }

    /** Outcome of the fold — what the engine reports for a candidate. */
    public record Result(double livePrior, boolean eliminated) {}

    /**
     * Folds the chain of firings that reference a candidate by id. Used by the multi-pass engine
     * to derive {@code live_prior} per candidate at Pass 6 synthesis.
     *
     * <p>Steps:
     * <ol>
     *   <li>Filter {@code allFirings} to those whose {@link Firing#getRefs()} contains
     *       {@code candidate.getId()}.</li>
     *   <li>Sort by {@code (roundNum, pass.order, firingId)} — the locked fold order per Q4.</li>
     *   <li>Extract their {@link PriorDelta}s and {@link #fold(double, List) fold}.</li>
     * </ol>
     *
     * <p>Default base prior is {@code 0.5} if the candidate has none set — visible-but-conservative.
     */
    public static Result foldChain(Firing candidate, List<Firing> allFirings) {
        String candId = Objects.requireNonNull(candidate.getId(), "candidate has no id");
        double basePrior = candidate.getBasePrior() == null ? 0.5 : candidate.getBasePrior();

        List<PriorDelta> deltas = allFirings.stream()
                .filter(f -> f.getRefs() != null && f.getRefs().contains(candId))
                .filter(f -> f.getPriorDelta() != null)
                .sorted(Comparator.<Firing>comparingInt(
                                f -> f.getRoundNum() == null ? 1 : f.getRoundNum())
                        .thenComparingInt(
                                f -> f.getPass() == null ? Pass.P6_SYNTHESIS.order : f.getPass().order)
                        .thenComparing(Firing::getId))
                .map(Firing::getPriorDelta)
                .toList();
        return fold(basePrior, deltas);
    }
}
