package com.dtech.ta.elliott.filter.conflict;

import com.dtech.ta.elliott.model.Direction;
import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.ScenarioConflictSet;
import com.dtech.ta.elliott.filter.domain.ScenarioFamilyCandidate;
import com.dtech.ta.elliott.filter.domain.ScenarioFamilyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class DefaultConflictResolver implements ConflictResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultConflictResolver.class);

    @Override
    public ScenarioConflictSet resolve(List<ScenarioFamilyCandidate> families, FilterConfig config) {
        if (families == null || families.isEmpty()) {
            return new ScenarioConflictSet("", "", List.of(), List.of(), List.of(),
                    "LOW_CONVICTION_NOISE", List.of());
        }

        List<ScenarioFamilyCandidate> bullish = families.stream()
                .filter(f -> f.directionalBias() == Direction.UP)
                .toList();
        List<ScenarioFamilyCandidate> bearish = families.stream()
                .filter(f -> f.directionalBias() == Direction.DOWN)
                .toList();
        List<ScenarioFamilyCandidate> neutral = families.stream()
                .filter(f -> f.directionalBias() == Direction.NEUTRAL)
                .toList();

        String mode = determineDominantConflictMode(bullish, bearish, neutral, families);

        List<String> explanation = buildExplanation(mode, bullish.size(), bearish.size(), neutral.size());

        String symbol = families.get(0).symbol();
        String tf = families.get(0).anchorTimeframe();

        log.debug("ConflictResolver: mode={}, bullish={}, bearish={}, neutral={}",
                mode, bullish.size(), bearish.size(), neutral.size());

        return new ScenarioConflictSet(symbol, tf, bullish, bearish, neutral, mode, explanation);
    }

    private String determineDominantConflictMode(
            List<ScenarioFamilyCandidate> bullish,
            List<ScenarioFamilyCandidate> bearish,
            List<ScenarioFamilyCandidate> neutral,
            List<ScenarioFamilyCandidate> all) {
        if (!bullish.isEmpty() && !bearish.isEmpty()) {
            return "BULLISH_VS_BEARISH";
        }
        if (neutral.size() > bullish.size() + bearish.size()) {
            return "TRENDING_VS_COMPRESSIVE";
        }
        boolean hasMotiveExtension = all.stream()
                .anyMatch(f -> f.familyType() == ScenarioFamilyType.MOTIVE_EXTENSION_STILL_ACTIVE);
        boolean hasCorrectiveComplexity = all.stream()
                .anyMatch(f -> f.familyType() == ScenarioFamilyType.ONGOING_CORRECTIVE_COMPLEXITY);
        if (hasMotiveExtension && hasCorrectiveComplexity) {
            return "CONTINUATION_VS_TERMINAL";
        }
        return "LOW_CONVICTION_NOISE";
    }

    private List<String> buildExplanation(String mode, int bullishCount, int bearishCount, int neutralCount) {
        List<String> explanation = new ArrayList<>();
        if ("BULLISH_VS_BEARISH".equals(mode)) {
            explanation.add("Bullish continuation family exists but bearish terminal family remains active");
        } else if ("TRENDING_VS_COMPRESSIVE".equals(mode)) {
            explanation.add("Compression family blocks directional confidence");
        } else if ("CONTINUATION_VS_TERMINAL".equals(mode)) {
            explanation.add("Motive extension and corrective complexity coexist");
        }
        explanation.add(String.format("Families: %d bullish, %d bearish, %d neutral",
                bullishCount, bearishCount, neutralCount));
        return List.copyOf(explanation);
    }
}
