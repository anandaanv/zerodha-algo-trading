package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.CopilotSkill;
import com.dtech.kitecon.repository.copilot.CopilotSkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * CRUD operations for CopilotSkill entities.
 * Prompt building delegated to {@link CopilotSkillPromptBuilder}.
 * Skill seeding delegated to {@link CopilotSkillSeederService}.
 */
@Service
@RequiredArgsConstructor
public class CopilotSkillService {

    private final CopilotSkillRepository skillRepository;
    private final CopilotSkillPromptBuilder promptBuilder;
    private final CopilotSkillSeederService seederService;

    public List<CopilotSkill> getAllSkillsForUser(Long userId) {
        return skillRepository.findByUserIdAndIsActiveTrue(userId);
    }

    public List<CopilotSkill> getSkillsByCategory(Long userId, String category) {
        return skillRepository.findByUserIdAndCategoryAndIsActiveTrue(userId, category);
    }

    public Optional<CopilotSkill> getSkillByKey(Long userId, String skillKey) {
        return skillRepository.findByUserIdAndSkillKey(userId, skillKey);
    }

    public Optional<CopilotSkill> getSkillById(Long id) {
        return skillRepository.findById(id);
    }

    @Transactional
    public CopilotSkill saveSkill(CopilotSkill skill) {
        return skillRepository.save(skill);
    }

    @Transactional
    public void deleteSkill(Long id) {
        skillRepository.findById(id).ifPresent(s -> {
            s.setIsActive(false);
            skillRepository.save(s);
        });
    }

    public List<CopilotSkill> getScanSkillsForUser(Long userId) {
        return skillRepository.findByUserIdAndIsActiveTrue(userId).stream()
                .filter(s -> !"REASONING".equals(s.getCategory()))
                .toList();
    }

    public List<CopilotSkill> getReasoningSkillsForUser(Long userId) {
        return skillRepository.findByUserIdAndCategoryAndIsActiveTrue(userId, "REASONING");
    }

    // --- Delegated to CopilotSkillPromptBuilder ---

    public String buildScanSkillPrompt(CopilotSkill skill) {
        return promptBuilder.buildScanSkillPrompt(skill);
    }

    public String buildSkillPrompt(CopilotSkill skill) {
        return promptBuilder.buildSkillPrompt(skill);
    }

    // --- Delegated to CopilotSkillSeederService ---

    @Transactional
    public void seedDemoSkillsForUser(Long userId) {
        seederService.seedDemoSkillsForUser(userId);
    }

    @Transactional
    public void seedReasoningSkillsForUser(Long userId) {
        seederService.seedReasoningSkillsForUser(userId);
    }

    @Transactional
    public int seedChartPatternSkillsForUser(Long userId) {
        return seederService.seedChartPatternSkillsForUser(userId);
    }

    @Transactional
    public int seedWaveSkillsForUser(Long userId) {
        return seederService.seedWaveSkillsForUser(userId);
    }
}
