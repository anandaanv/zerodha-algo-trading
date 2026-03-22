package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.CopilotInvestigation;
import com.dtech.kitecon.repository.copilot.CopilotInvestigationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CopilotInvestigationService {

    private final CopilotInvestigationRepository investigationRepository;

    /**
     * Get the currently active investigation for a layout+user, if it exists and hasn't expired.
     */
    public Optional<CopilotInvestigation> getActiveInvestigation(Long layoutId, Long userId) {
        return investigationRepository
                .findFirstByLayoutIdAndUserIdAndStatusOrderByCreatedAtDesc(layoutId, userId, "ACTIVE")
                .filter(inv -> !inv.isExpired());
    }

    /**
     * Get the latest previous investigation for context (even if expired/superseded).
     * Passed to orchestrator as historical context.
     */
    public Optional<CopilotInvestigation> getLatestInvestigation(Long layoutId, Long userId) {
        List<CopilotInvestigation> results = investigationRepository
                .findLatestByLayoutAndUser(layoutId, userId, PageRequest.of(0, 1));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Create a new investigation, marking any previous active one as SUPERSEDED.
     * Pending system-proposed drawings are cleared by the frontend on new investigation trigger.
     */
    @Transactional
    public CopilotInvestigation startInvestigation(Long layoutId, Long userId, String symbol,
                                                    String timeframesActive, int validityMinutes,
                                                    String initiatedBy) {
        // Mark any existing active investigation as superseded
        investigationRepository
                .findFirstByLayoutIdAndUserIdAndStatusOrderByCreatedAtDesc(layoutId, userId, "ACTIVE")
                .ifPresent(inv -> {
                    inv.setStatus("SUPERSEDED");
                    investigationRepository.save(inv);
                });

        CopilotInvestigation investigation = CopilotInvestigation.builder()
                .layoutId(layoutId)
                .userId(userId)
                .symbol(symbol)
                .timeframesActive(timeframesActive)
                .validityMinutes(validityMinutes)
                .initiatedBy(initiatedBy)
                .status("ACTIVE")
                .build();

        return investigationRepository.save(investigation);
    }

    /** Update investigation data fields (zigzag, drawings, market structure). */
    @Transactional
    public CopilotInvestigation updateData(Long investigationId, String zigzagData,
                                            String marketStructureData, String drawingsData) {
        CopilotInvestigation inv = getOrThrow(investigationId);
        if (zigzagData != null) inv.setZigzagData(zigzagData);
        if (marketStructureData != null) inv.setMarketStructureData(marketStructureData);
        if (drawingsData != null) inv.setDrawingsData(drawingsData);
        return investigationRepository.save(inv);
    }

    /** Record that a skill has been invoked (for cycle detection). */
    @Transactional
    public CopilotInvestigation recordSkillInvoked(Long investigationId, String skillKey, String resultJson) {
        CopilotInvestigation inv = getOrThrow(investigationId);

        // Append to invoked_skills list
        String currentInvoked = inv.getInvokedSkills();
        String updatedInvoked = appendToJsonArray(currentInvoked, "\"" + skillKey + "\"");
        inv.setInvokedSkills(updatedInvoked);

        // Merge into skill_results map
        String currentResults = inv.getSkillResults();
        String updatedResults = mergeIntoJsonMap(currentResults, skillKey, resultJson);
        inv.setSkillResults(updatedResults);

        return investigationRepository.save(inv);
    }

    /** Confirm wave count on the investigation. */
    @Transactional
    public CopilotInvestigation confirmWaveCount(Long investigationId, boolean confirmed, String source) {
        CopilotInvestigation inv = getOrThrow(investigationId);
        inv.setWaveCountConfirmed(confirmed);
        inv.setWaveCountSource(source);
        return investigationRepository.save(inv);
    }

    /** Update the investigation phase (PENDING → SCANNED → REASONED). */
    @Transactional
    public CopilotInvestigation updatePhase(Long investigationId, String phase) {
        CopilotInvestigation inv = getOrThrow(investigationId);
        inv.setPhase(phase);
        return investigationRepository.save(inv);
    }

    /** Expire an investigation. */
    @Transactional
    public void expireInvestigation(Long investigationId) {
        CopilotInvestigation inv = getOrThrow(investigationId);
        inv.setStatus("EXPIRED");
        investigationRepository.save(inv);
    }

    public CopilotInvestigation getOrThrow(Long id) {
        return investigationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Investigation not found: " + id));
    }

    // ─── JSON helpers (avoids pulling in Jackson dependency here) ────────────

    private String appendToJsonArray(String jsonArray, String element) {
        if (jsonArray == null || jsonArray.isBlank() || jsonArray.equals("[]")) {
            return "[" + element + "]";
        }
        return jsonArray.substring(0, jsonArray.lastIndexOf(']')) + "," + element + "]";
    }

    private String mergeIntoJsonMap(String jsonMap, String key, String value) {
        String entry = "\"" + key + "\":" + (value != null ? value : "null");
        // Treat null, blank, "{}", or "[]" (wrong init) all as empty map
        if (jsonMap == null || jsonMap.isBlank() || jsonMap.equals("{}") || !jsonMap.contains("{")) {
            return "{" + entry + "}";
        }
        return jsonMap.substring(0, jsonMap.lastIndexOf('}')) + "," + entry + "}";
    }
}
