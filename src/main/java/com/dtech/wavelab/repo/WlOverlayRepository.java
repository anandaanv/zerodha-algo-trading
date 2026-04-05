package com.dtech.wavelab.repo;

import com.dtech.wavelab.entity.WlOverlay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WlOverlayRepository extends JpaRepository<WlOverlay, Long> {
    List<WlOverlay> findBySymbolAndTimeframeOrderByCreatedAtDesc(String symbol, String timeframe);
    void deleteBySymbolAndTimeframe(String symbol, String timeframe);
}
