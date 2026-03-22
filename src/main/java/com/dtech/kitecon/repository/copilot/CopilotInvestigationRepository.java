package com.dtech.kitecon.repository.copilot;

import com.dtech.kitecon.data.copilot.CopilotInvestigation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CopilotInvestigationRepository extends JpaRepository<CopilotInvestigation, Long> {

    /** Get the most recent active investigation for a layout+user combination */
    Optional<CopilotInvestigation> findFirstByLayoutIdAndUserIdAndStatusOrderByCreatedAtDesc(
            Long layoutId, Long userId, String status);

    /** Get all active investigations for a user */
    List<CopilotInvestigation> findByUserIdAndStatus(Long userId, String status);

    /** Get latest N investigations for a layout (for passing as context) */
    @Query("SELECT i FROM CopilotInvestigation i WHERE i.layoutId = :layoutId AND i.userId = :userId ORDER BY i.createdAt DESC")
    List<CopilotInvestigation> findLatestByLayoutAndUser(Long layoutId, Long userId,
            org.springframework.data.domain.Pageable pageable);
}
