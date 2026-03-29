package com.dtech.kitecon.screener.elliott.service;

import com.dtech.kitecon.screener.elliott.dto.ElliottSuggestionResponse;
import com.dtech.kitecon.screener.elliott.entity.ElliottTradeSuggestion;
import com.dtech.kitecon.screener.elliott.entity.SuggestionState;
import com.dtech.kitecon.screener.elliott.repository.ElliottTradeSuggestionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ElliottTradeSuggestionService {

    private final ElliottTradeSuggestionRepository suggestionRepository;
    private final ObjectMapper objectMapper;

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
