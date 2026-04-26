package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.CopilotSkill;
import org.springframework.stereotype.Component;

/**
 * Builds prompt-ready representations of CopilotSkill objects.
 * Stateless — takes a skill, returns formatted text for AI consumption.
 */
@Component
public class CopilotSkillPromptBuilder {

    /**
     * Build a scan-mode prompt — only detection-relevant sections.
     * Skips Entry Rules (3) and Indicator Rules (4) since scan is observe-only.
     */
    public String buildScanSkillPrompt(CopilotSkill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SKILL: ").append(skill.getName()).append(" ===\n");
        sb.append("Category: ").append(skill.getCategory()).append("\n\n");

        appendSection(sb, "1. IDENTIFICATION RULES", skill.getIdentificationRules());
        appendSection(sb, "2. STAGE DETECTION", skill.getStageDetection());
        appendSection(sb, "5. INVALIDATION RULES", skill.getInvalidationRules());
        appendSection(sb, "7. CROSS-VERIFICATION RULES", skill.getCrossVerificationRules());

        return sb.toString();
    }

    /**
     * Build a full prompt with all 7 skill components.
     * This is what gets passed to the AI alongside the investigation context.
     */
    public String buildSkillPrompt(CopilotSkill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SKILL: ").append(skill.getName()).append(" ===\n");
        sb.append("Category: ").append(skill.getCategory()).append("\n\n");

        appendSection(sb, "1. IDENTIFICATION RULES", skill.getIdentificationRules());
        appendSection(sb, "2. STAGE DETECTION", skill.getStageDetection());
        appendSection(sb, "3. ENTRY RULES PER STAGE", skill.getEntryRules());
        appendSection(sb, "4. INDICATOR RULES PER STAGE", skill.getIndicatorRules());
        appendSection(sb, "5. INVALIDATION RULES", skill.getInvalidationRules());
        appendSection(sb, "6. AMBIGUITY QUESTIONS", skill.getAmbiguityQuestions());
        appendSection(sb, "7. CROSS-VERIFICATION RULES", skill.getCrossVerificationRules());

        return sb.toString();
    }

    private void appendSection(StringBuilder sb, String header, String content) {
        sb.append("--- ").append(header).append(" ---\n");
        sb.append(content != null ? content : "(Not yet populated)").append("\n\n");
    }
}
