package com.dtech.kitecon.repository.copilot;

import com.dtech.kitecon.data.copilot.CopilotHypothesisRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CopilotHypothesisRelationshipRepository extends JpaRepository<CopilotHypothesisRelationship, Long> {
    List<CopilotHypothesisRelationship> findByHypothesisIdOrRelatedHypothesisId(Long hypothesisId, Long relatedId);
    void deleteByHypothesisIdOrRelatedHypothesisId(Long hypothesisId, Long relatedId);
}
