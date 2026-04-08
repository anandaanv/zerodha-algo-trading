package com.dtech.kitecon.watchlist;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WatchlistSymbolRepository extends JpaRepository<WatchlistSymbol, Long> {
    Optional<WatchlistSymbol> findByWatchlistIdAndSymbol(Long watchlistId, String symbol);
    void deleteByWatchlistIdAndSymbol(Long watchlistId, String symbol);
}
