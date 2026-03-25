package com.dtech.ta.elliott;

import com.dtech.ta.elliott.confluence.ConfluenceZone;
import com.dtech.ta.elliott.hypothesis.HypothesisSnapshot;
import com.dtech.ta.elliott.scenario.ScoredScenario;
import com.dtech.ta.elliott.trigger.EntryCandidate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdvancedElliottAnalysisResult {
    private ElliottWaveAnalysis waveAnalysis;
    private List<ConfluenceZone> confluenceZones;
    private List<ScoredScenario> scoredScenarios;
    private List<EntryCandidate> entryCandidates;
    private HypothesisSnapshot hypothesisSnapshot;
    private String promptSummary;
}
