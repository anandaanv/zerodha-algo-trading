package com.dtech.kitecon.data.copilot;

import jakarta.persistence.*;
import lombok.*;

/**
 * Tracks the relationship between two hypotheses on the same symbol.
 * Evaluated by the system whenever multiple hypotheses exist.
 */
@Entity
@Table(name = "copilot_hypothesis_relationship",
        uniqueConstraints = @UniqueConstraint(columnNames = {"hypothesis_id", "related_hypothesis_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotHypothesisRelationship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hypothesis_id", nullable = false)
    private Long hypothesisId;

    @Column(name = "related_hypothesis_id", nullable = false)
    private Long relatedHypothesisId;

    /**
     * CONFLICTING — one being true makes the other structurally impossible
     * SEQUENTIAL — one plays out first, creating conditions for the other
     * INDEPENDENT — different timeframe degrees, both can be true simultaneously
     * REINFORCING — confirmation of one increases confidence in the other
     */
    @Column(name = "relationship_type", nullable = false, length = 30)
    private String relationshipType;

    /** AI-generated explanation of this relationship */
    @Column(name = "explanation", length = 500)
    private String explanation;
}
