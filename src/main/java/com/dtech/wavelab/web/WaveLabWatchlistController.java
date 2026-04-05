package com.dtech.wavelab.web;

import com.dtech.wavelab.entity.WlWatchlist;
import com.dtech.wavelab.entity.WlWatchlistItem;
import com.dtech.wavelab.service.WaveLabWatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/wave-lab/watchlists")
@CrossOrigin
public class WaveLabWatchlistController {

    private final WaveLabWatchlistService service;

    @GetMapping
    public ResponseEntity<List<WlWatchlist>> listWatchlists() {
        return ResponseEntity.ok(service.listWatchlists());
    }

    @PostMapping
    public ResponseEntity<WlWatchlist> createWatchlist(@RequestBody CreateWatchlistRequest request) {
        return ResponseEntity.ok(service.createWatchlist(request.name));
    }

    @PutMapping("/{id}")
    public ResponseEntity<WlWatchlist> renameWatchlist(@PathVariable Long id, @RequestBody CreateWatchlistRequest request) {
        return ResponseEntity.ok(service.renameWatchlist(id, request.name));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWatchlist(@PathVariable Long id) {
        service.deleteWatchlist(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/items")
    public ResponseEntity<List<WlWatchlistItem>> listItems(@PathVariable Long id) {
        return ResponseEntity.ok(service.listItems(id));
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<WlWatchlistItem> addItem(@PathVariable Long id, @RequestBody AddItemRequest request) {
        return ResponseEntity.ok(service.addItem(id, request.symbol, request.exchange));
    }

    @DeleteMapping("/{id}/items/{symbol}")
    public ResponseEntity<Void> removeItem(@PathVariable Long id, @PathVariable String symbol) {
        service.removeItem(id, symbol);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/import-csv")
    public ResponseEntity<List<WlWatchlistItem>> importCsv(@PathVariable Long id, @RequestBody String csvContent) {
        return ResponseEntity.ok(service.importCsv(id, csvContent));
    }

    @Data
    static class CreateWatchlistRequest {
        private String name;
    }

    @Data
    static class AddItemRequest {
        private String symbol;
        private String exchange;
    }
}
