package com.dtech.ta.elliott.filter.summary;

import com.dtech.ta.elliott.filter.domain.*;

import java.util.ArrayList;
import java.util.List;

public class DefaultHumanResearchSummaryBuilder implements HumanResearchSummaryBuilder {

    @Override
    public HumanResearchSummary build(FilteredScenarioSet set) {
        if (set == null) {
            return new HumanResearchSummary("", "", "No data available.",
                    List.of(), List.of(), List.of(), List.of());
        }

        String symbol = set.symbol() != null ? set.symbol() : "";
        String tf = set.anchorTimeframe() != null ? set.anchorTimeframe() : "";
        ScenarioFamilyCandidate leading = set.leadingScenario();

        String marketStateSummary = buildMarketState(leading, set.conflictSet());
        List<String> leadingSummary = buildLeadingSummary(leading);
        List<String> alternateSummary = buildAlternateSummary(set.activeAlternates());
        List<String> actionNotes = buildActionNotes(leading, set.activeAlternates());
        List<String> invalidationNotes = buildInvalidationNotes(leading, set.invalidatedFamilies());

        return new HumanResearchSummary(symbol, tf, marketStateSummary,
                leadingSummary, alternateSummary, actionNotes, invalidationNotes);
    }

    private String buildMarketState(ScenarioFamilyCandidate leading, ScenarioConflictSet conflictSet) {
        if (leading != null) {
            String conflictInfo = conflictSet != null && conflictSet.dominantConflictMode() != null
                    ? " Conflict mode: " + conflictSet.dominantConflictMode() + "."
                    : "";
            return "Leading scenario is " + leading.familyType().name()
                    + " with " + leading.directionalBias().name() + " bias." + conflictInfo;
        }
        String mode = conflictSet != null && conflictSet.dominantConflictMode() != null
                ? conflictSet.dominantConflictMode() : "UNKNOWN";
        return "Market remains in " + mode + " — no clear leading scenario.";
    }

    private List<String> buildLeadingSummary(ScenarioFamilyCandidate leading) {
        if (leading == null) return List.of("No leading scenario identified.");
        return List.of(
                "Direction: " + leading.directionalBias(),
                "Family: " + leading.familyType(),
                "Invalidation: " + leading.primaryInvalidationLevel(),
                "Trigger eligible: " + leading.triggerEligible(),
                "Patterns: " + leading.supportingPatterns()
        );
    }

    private List<String> buildAlternateSummary(List<ScenarioFamilyCandidate> alternates) {
        if (alternates == null || alternates.isEmpty()) return List.of("No active alternates.");
        List<String> summary = new ArrayList<>();
        for (ScenarioFamilyCandidate alt : alternates) {
            summary.add(alt.id() + " — " + alt.familyType() + " (" + alt.directionalBias() + ")");
        }
        return List.copyOf(summary);
    }

    private List<String> buildActionNotes(ScenarioFamilyCandidate leading,
                                           List<ScenarioFamilyCandidate> alternates) {
        List<String> notes = new ArrayList<>();
        if (leading == null) {
            notes.add("No high-conviction setup — stand aside or watch");
        } else {
            if (leading.triggerEligible()) notes.add("Watch for trigger near decision zone");
            if (leading.decisionZoneNearby()) notes.add("Price is near a decision zone");
        }
        int altCount = alternates != null ? alternates.size() : 0;
        notes.add("Alternates: " + altCount + " active alternate(s) remain valid");
        return List.copyOf(notes);
    }

    private List<String> buildInvalidationNotes(ScenarioFamilyCandidate leading,
                                                  List<ScenarioFamilyCandidate> invalidated) {
        List<String> notes = new ArrayList<>();
        if (leading != null) {
            notes.add("Leading invalidated if price crosses " + leading.primaryInvalidationLevel());
        }
        if (invalidated != null) {
            for (ScenarioFamilyCandidate fam : invalidated) {
                notes.add(fam.id() + " invalidated");
            }
        }
        return List.copyOf(notes);
    }
}
