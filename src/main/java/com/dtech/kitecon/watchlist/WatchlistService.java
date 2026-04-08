package com.dtech.kitecon.watchlist;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final WatchlistRepository watchlistRepo;
    private final WatchlistSymbolRepository symbolRepo;

    public List<WatchlistDto> listForUser(String userId) {
        return watchlistRepo.findByUserId(userId).stream().map(WatchlistDto::from).toList();
    }

    @Transactional
    public WatchlistDto create(String userId, String name) {
        Watchlist w = new Watchlist();
        w.setUserId(userId);
        w.setName(name);
        return WatchlistDto.from(watchlistRepo.save(w));
    }

    @Transactional
    public void delete(Long id, String userId) {
        Watchlist w = watchlistRepo.findById(id)
                .filter(wl -> wl.getUserId().equals(userId))
                .orElseThrow(() -> new RuntimeException("Watchlist not found"));
        watchlistRepo.delete(w);
    }

    public List<String> getSymbols(Long watchlistId) {
        return watchlistRepo.findById(watchlistId)
                .map(w -> w.getSymbols().stream().map(WatchlistSymbol::getSymbol).toList())
                .orElseThrow(() -> new RuntimeException("Watchlist not found"));
    }

    @Transactional
    public void addSymbol(Long watchlistId, String symbol) {
        Watchlist w = watchlistRepo.findById(watchlistId)
                .orElseThrow(() -> new RuntimeException("Watchlist not found"));
        if (symbolRepo.findByWatchlistIdAndSymbol(watchlistId, symbol).isPresent()) return;
        WatchlistSymbol ws = new WatchlistSymbol();
        ws.setWatchlist(w);
        ws.setSymbol(symbol.trim().toUpperCase());
        symbolRepo.save(ws);
    }

    @Transactional
    public void removeSymbol(Long watchlistId, String symbol) {
        symbolRepo.deleteByWatchlistIdAndSymbol(watchlistId, symbol);
    }

    @Transactional
    public int importCsv(Long watchlistId, String csvContent) {
        int count = 0;
        for (String line : csvContent.split("[\\r\\n]+")) {
            String sym = line.trim().toUpperCase();
            if (sym.isEmpty() || sym.startsWith("#")) continue;
            sym = sym.split(",")[0].trim();
            if (!sym.isEmpty()) {
                addSymbol(watchlistId, sym);
                count++;
            }
        }
        return count;
    }
}
