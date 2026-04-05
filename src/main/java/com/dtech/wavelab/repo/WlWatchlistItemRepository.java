package com.dtech.wavelab.repo;

import com.dtech.wavelab.entity.WlWatchlistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WlWatchlistItemRepository extends JpaRepository<WlWatchlistItem, Long> {
    List<WlWatchlistItem> findByWatchlistIdOrderByDisplayOrderAsc(Long watchlistId);
    void deleteByWatchlistId(Long watchlistId);
    Optional<WlWatchlistItem> findByWatchlistIdAndSymbol(Long watchlistId, String symbol);
    void deleteByWatchlistIdAndSymbol(Long watchlistId, String symbol);
}
