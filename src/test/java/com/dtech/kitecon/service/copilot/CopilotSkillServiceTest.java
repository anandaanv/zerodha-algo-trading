package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.CopilotSkill;
import com.dtech.kitecon.repository.copilot.CopilotSkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CopilotSkillServiceTest {

    @Mock
    private CopilotSkillRepository skillRepository;

    @InjectMocks
    private CopilotSkillService skillService;

    private CopilotSkill triangleSkill;
    private CopilotSkill wave4Skill;
    private CopilotSkill confluenceSkill;

    @BeforeEach
    void setUp() {
        triangleSkill = CopilotSkill.builder()
                .id(1L)
                .userId(100L)
                .name("Triangle Pattern")
                .description("Triangle pattern detection")
                .category("PATTERN")
                .skillKey("triangle")
                .isActive(true)
                .identificationRules("Rule 1")
                .stageDetection("Stage 1")
                .entryRules("Entry 1")
                .indicatorRules("Indicator 1")
                .invalidationRules("Invalid 1")
                .ambiguityQuestions("Q1")
                .crossVerificationRules("CV1")
                .build();

        wave4Skill = CopilotSkill.builder()
                .id(2L)
                .userId(100L)
                .name("Wave 4 Pattern")
                .description("Elliott Wave 4 analysis")
                .category("WAVE")
                .skillKey("wave_4")
                .isActive(true)
                .identificationRules("Rule 2")
                .stageDetection("Stage 2")
                .entryRules("Entry 2")
                .indicatorRules("Indicator 2")
                .invalidationRules("Invalid 2")
                .ambiguityQuestions("Q2")
                .crossVerificationRules("CV2")
                .build();

        confluenceSkill = CopilotSkill.builder()
                .id(3L)
                .userId(100L)
                .name("Confluence Checker")
                .description("Cross-correlate observations")
                .category("REASONING")
                .skillKey("confluence_checker")
                .isActive(true)
                .identificationRules("Rule 3")
                .stageDetection("Stage 3")
                .entryRules("Entry 3")
                .indicatorRules("Indicator 3")
                .invalidationRules("Invalid 3")
                .ambiguityQuestions("Q3")
                .crossVerificationRules("CV3")
                .build();
    }

    @Test
    void testGetAllSkillsForUser() {
        Long userId = 100L;
        when(skillRepository.findByUserIdAndIsActiveTrue(userId))
                .thenReturn(Arrays.asList(triangleSkill, wave4Skill, confluenceSkill));

        List<CopilotSkill> skills = skillService.getAllSkillsForUser(userId);

        assertNotNull(skills, "Skills list should not be null");
        assertEquals(3, skills.size(), "Should return 3 skills");
        verify(skillRepository, times(1)).findByUserIdAndIsActiveTrue(userId);
    }

    @Test
    void testGetSkillsByCategory() {
        Long userId = 100L;
        String category = "PATTERN";
        when(skillRepository.findByUserIdAndCategoryAndIsActiveTrue(userId, category))
                .thenReturn(Arrays.asList(triangleSkill));

        List<CopilotSkill> skills = skillService.getSkillsByCategory(userId, category);

        assertNotNull(skills, "Skills list should not be null");
        assertEquals(1, skills.size(), "Should return 1 PATTERN skill");
        assertEquals("PATTERN", skills.get(0).getCategory(), "Should be PATTERN category");
        verify(skillRepository, times(1)).findByUserIdAndCategoryAndIsActiveTrue(userId, category);
    }

    @Test
    void testGetSkillByKey() {
        Long userId = 100L;
        String skillKey = "triangle";
        when(skillRepository.findByUserIdAndSkillKey(userId, skillKey))
                .thenReturn(Optional.of(triangleSkill));

        Optional<CopilotSkill> skill = skillService.getSkillByKey(userId, skillKey);

        assertTrue(skill.isPresent(), "Skill should be present");
        assertEquals("triangle", skill.get().getSkillKey(), "Should match skill key");
        verify(skillRepository, times(1)).findByUserIdAndSkillKey(userId, skillKey);
    }

    @Test
    void testGetSkillByKeyNotFound() {
        Long userId = 100L;
        String skillKey = "nonexistent";
        when(skillRepository.findByUserIdAndSkillKey(userId, skillKey))
                .thenReturn(Optional.empty());

        Optional<CopilotSkill> skill = skillService.getSkillByKey(userId, skillKey);

        assertFalse(skill.isPresent(), "Skill should not be present");
    }

    @Test
    void testGetSkillById() {
        Long skillId = 1L;
        when(skillRepository.findById(skillId))
                .thenReturn(Optional.of(triangleSkill));

        Optional<CopilotSkill> skill = skillService.getSkillById(skillId);

        assertTrue(skill.isPresent(), "Skill should be present");
        assertEquals(1L, skill.get().getId(), "Should match skill ID");
        verify(skillRepository, times(1)).findById(skillId);
    }

    @Test
    void testSaveSkill() {
        when(skillRepository.save(triangleSkill))
                .thenReturn(triangleSkill);

        CopilotSkill saved = skillService.saveSkill(triangleSkill);

        assertNotNull(saved, "Saved skill should not be null");
        assertEquals("triangle", saved.getSkillKey(), "Should return saved skill");
        verify(skillRepository, times(1)).save(triangleSkill);
    }

    @Test
    void testDeleteSkillSoftDeletes() {
        Long skillId = 1L;
        CopilotSkill skillToDelete = CopilotSkill.builder()
                .id(1L)
                .userId(100L)
                .name("Triangle Pattern")
                .description("Triangle pattern detection")
                .category("PATTERN")
                .skillKey("triangle")
                .isActive(true)
                .build();

        when(skillRepository.findById(skillId))
                .thenReturn(Optional.of(skillToDelete));

        skillService.deleteSkill(skillId);

        ArgumentCaptor<CopilotSkill> captor = ArgumentCaptor.forClass(CopilotSkill.class);
        verify(skillRepository, times(1)).save(captor.capture());

        CopilotSkill savedSkill = captor.getValue();
        assertFalse(savedSkill.getIsActive(), "isActive should be marked as false");
        assertEquals(skillId, savedSkill.getId(), "Should preserve skill ID");
    }

    @Test
    void testDeleteSkillWhenNotFound() {
        Long skillId = 999L;
        when(skillRepository.findById(skillId))
                .thenReturn(Optional.empty());

        skillService.deleteSkill(skillId);

        verify(skillRepository, times(0)).save(any());
    }

    @Test
    void testGetScanSkillsForUserFiltersOutReasoning() {
        Long userId = 100L;
        List<CopilotSkill> allSkills = Arrays.asList(triangleSkill, wave4Skill, confluenceSkill);
        when(skillRepository.findByUserIdAndIsActiveTrue(userId))
                .thenReturn(allSkills);

        List<CopilotSkill> scanSkills = skillService.getScanSkillsForUser(userId);

        assertEquals(2, scanSkills.size(), "Should return 2 non-REASONING skills");
        assertFalse(scanSkills.stream().anyMatch(s -> "REASONING".equals(s.getCategory())),
                "Should not contain REASONING skills");
    }

    @Test
    void testGetReasoningSkillsForUserFiltersOnlyReasoning() {
        Long userId = 100L;
        when(skillRepository.findByUserIdAndCategoryAndIsActiveTrue(userId, "REASONING"))
                .thenReturn(Arrays.asList(confluenceSkill));

        List<CopilotSkill> reasoningSkills = skillService.getReasoningSkillsForUser(userId);

        assertEquals(1, reasoningSkills.size(), "Should return 1 REASONING skill");
        assertEquals("REASONING", reasoningSkills.get(0).getCategory(), "Should be REASONING category");
        verify(skillRepository, times(1)).findByUserIdAndCategoryAndIsActiveTrue(userId, "REASONING");
    }

    @Test
    void testBuildSkillPromptIncludesAllSections() {
        String prompt = skillService.buildSkillPrompt(triangleSkill);

        assertNotNull(prompt, "Prompt should not be null");
        assertTrue(prompt.contains("Triangle Pattern"), "Should include skill name");
        assertTrue(prompt.contains("PATTERN"), "Should include category");
        assertTrue(prompt.contains("1. IDENTIFICATION RULES"), "Should include section 1");
        assertTrue(prompt.contains("2. STAGE DETECTION"), "Should include section 2");
        assertTrue(prompt.contains("3. ENTRY RULES"), "Should include section 3");
        assertTrue(prompt.contains("4. INDICATOR RULES"), "Should include section 4");
        assertTrue(prompt.contains("5. INVALIDATION RULES"), "Should include section 5");
        assertTrue(prompt.contains("6. AMBIGUITY QUESTIONS"), "Should include section 6");
        assertTrue(prompt.contains("7. CROSS-VERIFICATION RULES"), "Should include section 7");
    }

    @Test
    void testBuildScanSkillPromptIncludesOnly1257() {
        String prompt = skillService.buildScanSkillPrompt(triangleSkill);

        assertNotNull(prompt, "Prompt should not be null");
        assertTrue(prompt.contains("Triangle Pattern"), "Should include skill name");
        assertTrue(prompt.contains("1. IDENTIFICATION RULES"), "Should include section 1");
        assertTrue(prompt.contains("2. STAGE DETECTION"), "Should include section 2");
        assertTrue(prompt.contains("5. INVALIDATION RULES"), "Should include section 5");
        assertTrue(prompt.contains("7. CROSS-VERIFICATION RULES"), "Should include section 7");

        // Sections 3 and 4 should NOT be in scan mode
        assertFalse(prompt.contains("3. ENTRY RULES"), "Should NOT include section 3");
        assertFalse(prompt.contains("4. INDICATOR RULES"), "Should NOT include section 4");
    }

    @Test
    void testBuildSkillPromptWithNullContent() {
        CopilotSkill skillWithNulls = CopilotSkill.builder()
                .id(4L)
                .userId(100L)
                .name("Test Skill")
                .category("PATTERN")
                .skillKey("test_skill")
                .isActive(true)
                .identificationRules(null)
                .stageDetection(null)
                .entryRules(null)
                .indicatorRules(null)
                .invalidationRules(null)
                .ambiguityQuestions(null)
                .crossVerificationRules(null)
                .build();

        String prompt = skillService.buildSkillPrompt(skillWithNulls);

        assertNotNull(prompt, "Prompt should not be null");
        assertTrue(prompt.contains("Not yet populated"), "Should indicate missing content");
    }

    @Test
    void testSeedDemoSkillsForUserIdempotent() {
        Long userId = 100L;

        // First call: skills don't exist
        when(skillRepository.findByUserIdAndSkillKey(userId, "triangle"))
                .thenReturn(Optional.empty());
        when(skillRepository.findByUserIdAndSkillKey(userId, "wave_4"))
                .thenReturn(Optional.empty());

        skillService.seedDemoSkillsForUser(userId);

        verify(skillRepository, times(2)).save(any(CopilotSkill.class));

        // Second call: skills already exist
        reset(skillRepository);
        when(skillRepository.findByUserIdAndSkillKey(userId, "triangle"))
                .thenReturn(Optional.of(triangleSkill));

        skillService.seedDemoSkillsForUser(userId);

        verify(skillRepository, times(0)).save(any());
    }

    @Test
    void testSeedReasoningSkillsForUserIdempotent() {
        Long userId = 100L;

        // First call: skills don't exist
        when(skillRepository.findByUserIdAndSkillKey(userId, "confluence_checker"))
                .thenReturn(Optional.empty());

        skillService.seedReasoningSkillsForUser(userId);

        verify(skillRepository, times(2)).save(any(CopilotSkill.class));

        // Second call: skills already exist
        reset(skillRepository);
        when(skillRepository.findByUserIdAndSkillKey(userId, "confluence_checker"))
                .thenReturn(Optional.of(confluenceSkill));

        skillService.seedReasoningSkillsForUser(userId);

        verify(skillRepository, times(0)).save(any());
    }

    @Test
    void testSeedChartPatternSkillsForUser() {
        Long userId = 100L;

        // Mock all chart pattern skills as not existing
        when(skillRepository.findByUserIdAndSkillKey(eq(userId), any()))
                .thenReturn(Optional.empty());

        int count = skillService.seedChartPatternSkillsForUser(userId);

        assertEquals(18, count, "Should seed 18 chart pattern skills");
        verify(skillRepository, times(18)).save(any(CopilotSkill.class));
    }

    @Test
    void testSeedChartPatternSkillsForUserIdempotent() {
        Long userId = 100L;

        // First call: no skills exist
        when(skillRepository.findByUserIdAndSkillKey(eq(userId), any()))
                .thenReturn(Optional.empty());

        int count1 = skillService.seedChartPatternSkillsForUser(userId);
        assertEquals(18, count1, "Should seed 18 skills on first call");

        // Second call: all skills already exist
        reset(skillRepository);
        when(skillRepository.findByUserIdAndSkillKey(eq(userId), any()))
                .thenReturn(Optional.of(triangleSkill));

        int count2 = skillService.seedChartPatternSkillsForUser(userId);
        assertEquals(0, count2, "Should seed 0 skills on second call (idempotent)");
        verify(skillRepository, times(0)).save(any());
    }

    @Test
    void testGetAllSkillsForUserEmpty() {
        Long userId = 200L;
        when(skillRepository.findByUserIdAndIsActiveTrue(userId))
                .thenReturn(Arrays.asList());

        List<CopilotSkill> skills = skillService.getAllSkillsForUser(userId);

        assertNotNull(skills, "Skills list should not be null");
        assertTrue(skills.isEmpty(), "Skills list should be empty");
    }

    @Test
    void testBuildScanSkillPromptFormat() {
        String prompt = skillService.buildScanSkillPrompt(wave4Skill);

        assertNotNull(prompt, "Prompt should not be null");
        assertTrue(prompt.contains("=== SKILL:"), "Should have skill header");
        assertTrue(prompt.contains("Category:"), "Should include category line");
        assertTrue(prompt.contains("---"), "Should have section separators");
    }
}
