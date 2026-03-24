package com.dtech.ta.elliott;

import lombok.Builder;
import lombok.Data;

/**
 * A single piece of evidence supporting or contradicting a wave count.
 * Code never draws conclusions — it only lists facts with evidence type.
 * The AI layer reads these and makes the final judgment.
 */
@Data
@Builder
public class WaveEvidence {

    public enum EvidenceType { SUPPORTS, CONTRADICTS, NEUTRAL }

    private EvidenceType type;

    /** Which indicator or rule this evidence comes from */
    private String source;

    /** Human-readable observation (no interpretation, just fact) */
    private String observation;

    /** Optional numeric value associated with this evidence */
    private Double value;

    // ── Factory helpers ───────────────────────────────────────────────────────

    public static WaveEvidence supports(String source, String observation) {
        return WaveEvidence.builder().type(EvidenceType.SUPPORTS).source(source).observation(observation).build();
    }

    public static WaveEvidence supports(String source, String observation, double value) {
        return WaveEvidence.builder().type(EvidenceType.SUPPORTS).source(source).observation(observation).value(value).build();
    }

    public static WaveEvidence contradicts(String source, String observation) {
        return WaveEvidence.builder().type(EvidenceType.CONTRADICTS).source(source).observation(observation).build();
    }

    public static WaveEvidence contradicts(String source, String observation, double value) {
        return WaveEvidence.builder().type(EvidenceType.CONTRADICTS).source(source).observation(observation).value(value).build();
    }

    public static WaveEvidence neutral(String source, String observation) {
        return WaveEvidence.builder().type(EvidenceType.NEUTRAL).source(source).observation(observation).build();
    }
}
