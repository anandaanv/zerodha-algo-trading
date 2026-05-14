package com.dtech.aitrader.annotation.repository;

import com.dtech.aitrader.annotation.entity.SymbolThesis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SymbolThesisRepository extends JpaRepository<SymbolThesis, Long> {

    Optional<SymbolThesis> findByUserIdAndTabUuidAndSymbol(Long userId, String tabUuid, String symbol);

    Optional<SymbolThesis> findFirstByUserIdAndSymbolOrderByUpdatedAtDesc(Long userId, String symbol);
}
