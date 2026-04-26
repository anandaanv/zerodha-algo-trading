package com.dtech.ta.elliott.filter.compress;

import com.dtech.ta.elliott.model.Direction;
import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.*;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class DefaultScenarioCompressor implements ScenarioCompressor {

    private static final Logger log = LoggerFactory.getLogger(DefaultScenarioCompressor.class);

    @Override
    public FilteredScenarioSet compress(List<ScenarioFamilyCandidate> scoredFamilies,
                                         ScenarioConflictSet conflictSet,
                                         FilterConfig config) {
        String symbol = symbolFrom(scoredFamilies);
        String tf = timeframeFrom(scoredFamilies);

        if (scoredFamilies == null || scoredFamilies.isEmpty()) {
            return emptySet(symbol, tf, conflictSet);
        }

        // Separate invalidated
        List<ScenarioFamilyCandidate> invalidated = scoredFamilies.stream()
                .filter(f -> f.status() == ScenarioStatus.INVALIDATED)
                .toList();

        List<ScenarioFamilyCandidate> active = scoredFamilies.stream()
                .filter(f -> f.status() != ScenarioStatus.INVALIDATED)
                .collect(Collectors.toCollection(ArrayList::new));

        // Optionally suppress NO_TRADE_NOISE
        if (config.suppressNoTradeNoiseFamilies()) {
            active.removeIf(f -> f.familyType() == ScenarioFamilyType.NO_TRADE_NOISE);
        }

        // Sort by finalRankScore descending with tie-breakers (spec section 11)
        active.sort((a, b) -> {
            int cmp = Double.compare(b.score().finalRankScore(), a.score().finalRankScore());
            if (cmp != 0) return cmp;
            cmp = Double.compare(b.score().structuralStrength(), a.score().structuralStrength());
            if (cmp != 0) return cmp;
            cmp = Double.compare(b.score().confluenceStrength(), a.score().confluenceStrength());
            if (cmp != 0) return cmp;
            cmp = Double.compare(a.score().ambiguityPenalty(), b.score().ambiguityPenalty()); // ascending
            if (cmp != 0) return cmp;
            cmp = Double.compare(b.score().tradeUtility(), a.score().tradeUtility());
            if (cmp != 0) return cmp;
            return a.id().compareTo(b.id());
        });

        if (active.isEmpty()) {
            return emptySet(symbol, tf, conflictSet);
        }

        // Select leading
        ScenarioFamilyCandidate leading = null;
        int startAlternatesFrom = 0;
        ScenarioFamilyCandidate top = active.get(0);
        if (top.score().ambiguityPenalty() <= config.ambiguityCeilingForLeadingScenario()) {
            leading = top;
            startAlternatesFrom = 1;
        }

        // Select active alternates
        List<ScenarioFamilyCandidate> remaining = new ArrayList<>(active.subList(startAlternatesFrom, active.size()));
        List<ScenarioFamilyCandidate> alternates = new ArrayList<>();
        for (int i = 0; i < remaining.size() && alternates.size() < config.maxActiveAlternates(); i++) {
            alternates.add(remaining.get(i));
        }

        // Optionally keep contrarian alternate
        if (config.keepContrarianAlternate() && leading != null) {
            Direction leadingDir = leading.directionalBias();
            boolean hasContrarian = alternates.stream()
                    .anyMatch(f -> f.directionalBias() != leadingDir && f.directionalBias() != Direction.NEUTRAL);
            if (!hasContrarian) {
                remaining.stream()
                        .filter(f -> !alternates.contains(f))
                        .filter(f -> f.directionalBias() != leadingDir && f.directionalBias() != Direction.NEUTRAL)
                        .findFirst()
                        .ifPresent(contrarian -> {
                            if (alternates.size() >= config.maxActiveAlternates()) {
                                alternates.set(alternates.size() - 1, contrarian);
                            } else {
                                alternates.add(contrarian);
                            }
                        });
            }
        }

        // Select weak alternates
        Set<String> selected = new HashSet<>();
        if (leading != null) selected.add(leading.id());
        alternates.forEach(a -> selected.add(a.id()));

        List<ScenarioFamilyCandidate> weakAlternates = remaining.stream()
                .filter(f -> !selected.contains(f.id()))
                .limit(config.maxWeakAlternates())
                .toList();

        log.debug("Compressor: leading={}, alternates={}, weak={}, invalidated={}",
                leading != null ? leading.familyType() : "none",
                alternates.size(), weakAlternates.size(), invalidated.size());

        return new FilteredScenarioSet(symbol, tf, leading, List.copyOf(alternates),
                weakAlternates, invalidated, conflictSet, null, null);
    }

    private FilteredScenarioSet emptySet(String symbol, String tf, ScenarioConflictSet conflictSet) {
        return new FilteredScenarioSet(symbol, tf, null, List.of(), List.of(), List.of(),
                conflictSet, null, null);
    }

    private String symbolFrom(List<ScenarioFamilyCandidate> families) {
        if (families == null || families.isEmpty()) return "";
        return families.stream().map(ScenarioFamilyCandidate::symbol)
                .filter(s -> s != null && !s.isBlank()).findFirst().orElse("");
    }

    private String timeframeFrom(List<ScenarioFamilyCandidate> families) {
        if (families == null || families.isEmpty()) return "";
        return families.stream().map(ScenarioFamilyCandidate::anchorTimeframe)
                .filter(s -> s != null && !s.isBlank()).findFirst().orElse("");
    }
}
