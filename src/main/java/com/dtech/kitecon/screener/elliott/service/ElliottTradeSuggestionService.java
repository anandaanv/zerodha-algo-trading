package com.dtech.kitecon.screener.elliott.service;

import com.dtech.kitecon.screener.elliott.dto.ElliottSuggestionResponse;
import com.dtech.kitecon.screener.elliott.entity.ElliottTradeSuggestion;
import com.dtech.kitecon.screener.elliott.entity.SuggestionState;
import com.dtech.kitecon.screener.elliott.repository.ElliottTradeSuggestionRepository;
import com.dtech.kitecon.scan.entity.OnDemandScan;
import com.dtech.kitecon.scan.repository.OnDemandScanRepository;
import com.dtech.kitecon.service.copilot.dto.FindingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ElliottTradeSuggestionService {

    private final ElliottTradeSuggestionRepository suggestionRepository;
    private final ObjectMapper objectMapper;
    private final OnDemandScanRepository scanRepository;

    public List<ElliottSuggestionResponse> listForUser(Long userId, List<SuggestionState> stateFilter) {
        List<ElliottTradeSuggestion> suggestions = (stateFilter == null || stateFilter.isEmpty())
                ? suggestionRepository.findByUserIdOrderByCreatedAtDesc(userId)
                : suggestionRepository.findByUserIdAndStateInOrderByCreatedAtDesc(userId, stateFilter);
        return suggestions.stream().map(s -> ElliottSuggestionResponse.from(s, objectMapper)).toList();
    }

    public ElliottSuggestionResponse get(Long userId, Long suggestionId) {
        ElliottTradeSuggestion s = loadOwned(userId, suggestionId);
        return ElliottSuggestionResponse.from(s, objectMapper);
    }

    public List<ElliottSuggestionResponse> listForScreener(Long userId, Long screenerId) {
        return suggestionRepository.findByScreenerIdOrderByCreatedAtDesc(screenerId).stream()
                .filter(s -> s.getUserId().equals(userId))
                .map(s -> ElliottSuggestionResponse.from(s, objectMapper))
                .toList();
    }

    public ElliottSuggestionResponse createFromScan(Long scanId, Long userId) {
        OnDemandScan scan = scanRepository.findById(scanId)
                .orElseThrow(() -> new IllegalArgumentException("Scan not found: " + scanId));

        // Parse resultJson to get aiParsed node
        JsonNode aiParsed;
        try {
            JsonNode result = objectMapper.readTree(scan.getResultJson());
            aiParsed = result.path("aiParsed");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse scan result JSON: " + e.getMessage());
        }

        if (aiParsed.isMissingNode() || aiParsed.isNull()) {
            throw new IllegalStateException("Scan has no AI parsed result (no setup found)");
        }

        JsonNode entry = aiParsed.path("anticipatoryEntry");
        String direction = entry.path("direction").asText(null);
        if (direction == null || direction.isBlank()) {
            // Infer from pattern if not set
            String pattern = aiParsed.path("pattern").asText("");
            direction = pattern.toUpperCase().contains("TOP") || pattern.toUpperCase().contains("BEAR")
                    || pattern.toUpperCase().contains("HEAD_AND_SHOULDERS") ? "SHORT" : "LONG";
        }

        String entryZoneText  = entry.path("entryZone").asText(null);
        String slText         = firstNonEmpty(entry.path("sl").asText(null), entry.path("stopLoss").asText(null));
        String tpText         = firstNonEmpty(entry.path("tp").asText(null), entry.path("target1").asText(null));
        String triggerDesc    = entry.path("triggerDescription").asText(null);

        BigDecimal[] zone = parseEntryZone(entryZoneText);
        BigDecimal slPrice = parseSinglePrice(slText);
        BigDecimal t1Price = parseSinglePrice(tpText);

        ElliottTradeSuggestion suggestion = ElliottTradeSuggestion.builder()
                .userId(userId)
                .sourceScanId(scanId)
                .symbol(scan.getSymbol())
                .primaryTimeframe(scan.getPrimaryTimeframe())
                .allTimeframes(scan.getAllTimeframes())
                .direction(direction)
                .state(SuggestionState.ANTICIPATORY)
                .hypothesisLabel(aiParsed.path("hypothesisLabel").asText(null))
                .waveContext(aiParsed.path("waveContext").asText(null))
                .pattern(aiParsed.path("pattern").asText(null))
                .currentStage(aiParsed.path("currentStage").asText(null))
                .entryZone(entryZoneText)
                .stopLoss(slText)
                .target1(tpText)
                .entryLow(zone[0])
                .entryHigh(zone[1])
                .stopLossPrice(slPrice)
                .target1Price(t1Price)
                .triggerDescription(triggerDesc)
                .reasoning(aiParsed.path("reasoning").asText(null))
                .rawAiResponse(scan.getResultJson())
                .acceptedAt(Instant.now())
                .build();

        return ElliottSuggestionResponse.from(suggestionRepository.save(suggestion), objectMapper);
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isBlank() && !"null".equals(a)) return a;
        if (b != null && !b.isBlank() && !"null".equals(b)) return b;
        return null;
    }

    private static BigDecimal[] parseEntryZone(String text) {
        if (text == null || text.isBlank()) return new BigDecimal[]{null, null};
        String t = text.trim();

        // "430-435" or "430 - 435" (dash/en-dash)
        Matcher range = Pattern.compile("([\\d,]+\\.?\\d*)\\s*[-–]\\s*([\\d,]+\\.?\\d*)").matcher(t);
        if (range.find()) {
            return new BigDecimal[]{
                new BigDecimal(range.group(1).replace(",", "")),
                new BigDecimal(range.group(2).replace(",", ""))
            };
        }

        // "below 420" / "under 420"
        Matcher below = Pattern.compile("(?i)(below|under)\\s*([\\d,]+\\.?\\d*)").matcher(t);
        if (below.find()) {
            BigDecimal hi = new BigDecimal(below.group(2).replace(",", ""));
            return new BigDecimal[]{hi.multiply(BigDecimal.valueOf(0.97)).setScale(2, java.math.RoundingMode.HALF_UP), hi};
        }

        // "above 420" / "over 420"
        Matcher above = Pattern.compile("(?i)(above|over)\\s*([\\d,]+\\.?\\d*)").matcher(t);
        if (above.find()) {
            BigDecimal lo = new BigDecimal(above.group(2).replace(",", ""));
            return new BigDecimal[]{lo, lo.multiply(BigDecimal.valueOf(1.03)).setScale(2, java.math.RoundingMode.HALF_UP)};
        }

        // Single number "420.50" (or with trailing text)
        Matcher num = Pattern.compile("([\\d,]+\\.?\\d*)").matcher(t);
        if (num.find()) {
            BigDecimal price = new BigDecimal(num.group(1).replace(",", ""));
            return new BigDecimal[]{
                price.multiply(BigDecimal.valueOf(0.99)).setScale(2, java.math.RoundingMode.HALF_UP),
                price.multiply(BigDecimal.valueOf(1.01)).setScale(2, java.math.RoundingMode.HALF_UP)
            };
        }

        return new BigDecimal[]{null, null};
    }

    private static BigDecimal parseSinglePrice(String text) {
        if (text == null || text.isBlank() || "null".equals(text)) return null;
        Matcher m = Pattern.compile("([\\d,]+\\.?\\d*)").matcher(text.trim());
        return m.find() ? new BigDecimal(m.group(1).replace(",", "")) : null;
    }

    public ElliottSuggestionResponse accept(Long userId, Long suggestionId, String userNotes) {
        ElliottTradeSuggestion s = loadOwned(userId, suggestionId);
        assertState(s, SuggestionState.PROPOSED);
        s.setState(SuggestionState.ANTICIPATORY);
        s.setAcceptedAt(Instant.now());
        s.setUserNotes(userNotes);
        return ElliottSuggestionResponse.from(suggestionRepository.save(s), objectMapper);
    }

    public ElliottSuggestionResponse activate(Long userId, Long suggestionId, String userNotes) {
        ElliottTradeSuggestion s = loadOwned(userId, suggestionId);
        assertState(s, SuggestionState.ANTICIPATORY);
        s.setState(SuggestionState.ACTIVE);
        s.setActivatedAt(Instant.now());
        appendNotes(s, userNotes);
        return ElliottSuggestionResponse.from(suggestionRepository.save(s), objectMapper);
    }

    public ElliottSuggestionResponse close(Long userId, Long suggestionId, boolean successful, String userNotes) {
        ElliottTradeSuggestion s = loadOwned(userId, suggestionId);
        if (s.getState() != SuggestionState.ACTIVE && s.getState() != SuggestionState.ANTICIPATORY) {
            throw new IllegalStateException("Cannot close suggestion in state " + s.getState());
        }
        s.setState(successful ? SuggestionState.SUCCESSFUL : SuggestionState.FAILED);
        s.setClosedAt(Instant.now());
        appendNotes(s, userNotes);
        return ElliottSuggestionResponse.from(suggestionRepository.save(s), objectMapper);
    }

    public ElliottSuggestionResponse reject(Long userId, Long suggestionId, String userNotes) {
        ElliottTradeSuggestion s = loadOwned(userId, suggestionId);
        assertState(s, SuggestionState.PROPOSED);
        s.setState(SuggestionState.REJECTED);
        s.setClosedAt(Instant.now());
        s.setUserNotes(userNotes);
        return ElliottSuggestionResponse.from(suggestionRepository.save(s), objectMapper);
    }

    private ElliottTradeSuggestion loadOwned(Long userId, Long suggestionId) {
        return suggestionRepository.findByIdAndUserId(suggestionId, userId)
                .orElseThrow(() -> new IllegalStateException("Suggestion not found or access denied"));
    }

    private void assertState(ElliottTradeSuggestion s, SuggestionState expected) {
        if (s.getState() != expected) {
            throw new IllegalStateException("Cannot perform action on suggestion in state " + s.getState());
        }
    }

    private void appendNotes(ElliottTradeSuggestion s, String notes) {
        if (notes == null || notes.isBlank()) return;
        String existing = s.getUserNotes();
        s.setUserNotes(existing != null ? existing + "\n" + notes : notes);
    }
}
