package com.dtech.kitecon.repository.copilot;

import com.dtech.kitecon.data.copilot.CopilotSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CopilotSkillRepository extends JpaRepository<CopilotSkill, Long> {
    List<CopilotSkill> findByUserIdAndIsActiveTrue(Long userId);
    List<CopilotSkill> findByUserIdAndCategoryAndIsActiveTrue(Long userId, String category);
    Optional<CopilotSkill> findByUserIdAndSkillKey(Long userId, String skillKey);
    List<CopilotSkill> findByIsSystemSeedTrue();
}
