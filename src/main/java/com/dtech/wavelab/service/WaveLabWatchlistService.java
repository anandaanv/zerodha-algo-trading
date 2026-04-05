package com.dtech.wavelab.service;

import com.dtech.wavelab.entity.WlWatchlist;
import com.dtech.wavelab.entity.WlWatchlistItem;
import com.dtech.wavelab.repo.WlWatchlistItemRepository;
import com.dtech.wavelab.repo.WlWatchlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class WaveLabWatchlistService {

    private final WlWatchlistRepository watchlistRepo;
    private final WlWatchlistItemRepository itemRepo;

    public List<WlWatchlist> listWatchlists() {
        return watchlistRepo.findAllByOrderByCreatedAtDesc();
    }

    public WlWatchlist createWatchlist(String name) {
        WlWatchlist watchlist = WlWatchlist.builder()
                .name(name)
                .build();
        return watchlistRepo.save(watchlist);
    }

    public void deleteWatchlist(Long id) {
        itemRepo.deleteByWatchlistId(id);
        watchlistRepo.deleteById(id);
    }

    public List<WlWatchlistItem> listItems(Long watchlistId) {
        return itemRepo.findByWatchlistIdOrderByDisplayOrderAsc(watchlistId);
    }

    public WlWatchlistItem addItem(Long watchlistId, String symbol, String exchange) {
        // Check if item already exists
        var existing = itemRepo.findByWatchlistIdAndSymbol(watchlistId, symbol);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Get current count to determine display order
        List<WlWatchlistItem> items = listItems(watchlistId);
        Integer displayOrder = items.size() + 1;

        WlWatchlistItem item = WlWatchlistItem.builder()
                .watchlistId(watchlistId)
                .symbol(symbol)
                .exchange(exchange != null ? exchange : "NSE")
                .displayOrder(displayOrder)
                .build();
        return itemRepo.save(item);
    }

    public void removeItem(Long watchlistId, String symbol) {
        itemRepo.deleteByWatchlistIdAndSymbol(watchlistId, symbol);
    }

    public List<WlWatchlistItem> importCsv(Long watchlistId, String csvContent) {
        if (csvContent == null || csvContent.trim().isEmpty()) {
            return listItems(watchlistId);
        }

        String[] lines = csvContent.split("\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Split by comma and take first token as symbol
            String[] parts = trimmed.split(",");
            if (parts.length > 0) {
                String symbol = parts[0].trim();
                if (!symbol.isEmpty()) {
                    // Default exchange to NSE
                    addItem(watchlistId, symbol, "NSE");
                }
            }
        }

        return listItems(watchlistId);
    }

    public WlWatchlist renameWatchlist(Long id, String name) {
        WlWatchlist watchlist = watchlistRepo.findById(id).orElseThrow();
        watchlist.setName(name);
        return watchlistRepo.save(watchlist);
    }
}
