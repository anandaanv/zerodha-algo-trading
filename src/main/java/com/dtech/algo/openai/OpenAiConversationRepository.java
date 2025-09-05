package com.dtech.algo.openai;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OpenAiConversationRepository extends JpaRepository<OpenAiConversation, Long> {

  Optional<OpenAiConversation> findFirstBySymbolAndTimeframeOrderByIdDesc(String symbol, String timeframe);

  Optional<OpenAiConversation> findBySymbolAndTimeframe(String symbol, String timeframe);
}
