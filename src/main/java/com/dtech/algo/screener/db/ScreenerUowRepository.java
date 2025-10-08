package com.dtech.algo.screener.db;

import com.dtech.algo.screener.enums.WorkflowStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScreenerUowRepository extends JpaRepository<ScreenerUowEntity, Long> {
    List<ScreenerUowEntity> findByScreenerRunId(Long screenerRunId);
    List<ScreenerUowEntity> findByStepType(WorkflowStep stepType);

    Optional<ScreenerUowEntity> findTopByScreenerRunIdAndStepTypeOrderByCreatedAtDesc(Long screenerRunId, WorkflowStep stepType);
}
