package com.dtech.kitecon.screener.elliott.repository;

import com.dtech.kitecon.screener.elliott.entity.ElliottTradeSuggestion;
import com.dtech.kitecon.screener.elliott.entity.SuggestionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ElliottTradeSuggestionRepository extends JpaRepository<ElliottTradeSuggestion, Long> {
    List<ElliottTradeSuggestion> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<ElliottTradeSuggestion> findByUserIdAndStateInOrderByCreatedAtDesc(Long userId, List<SuggestionState> states);
    List<ElliottTradeSuggestion> findByScreenerIdOrderByCreatedAtDesc(Long screenerId);
    List<ElliottTradeSuggestion> findByRunId(Long runId);
    boolean existsByScreenerIdAndSymbolAndDirectionAndStateIn(Long screenerId, String symbol, String direction, List<SuggestionState> states);
    List<ElliottTradeSuggestion> findByUserIdAndSymbolAndStateIn(Long userId, String symbol, List<SuggestionState> states);
    Optional<ElliottTradeSuggestion> findByIdAndUserId(Long id, Long userId);
    List<ElliottTradeSuggestion> findByScreenerIdAndSymbolAndStateIn(Long screenerId, String symbol, List<SuggestionState> states);
}
