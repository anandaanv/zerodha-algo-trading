package com.dtech.kitecon.watchlist;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/watchlists")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService service;

    @GetMapping
    public List<WatchlistDto> list(Authentication auth) {
        return service.listForUser(auth.getName());
    }

    @PostMapping
    public WatchlistDto create(Authentication auth, @RequestBody Map<String, String> body) {
        return service.create(auth.getName(), body.get("name"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long id) {
        service.delete(id, auth.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/symbols")
    public List<String> symbols(@PathVariable Long id) {
        return service.getSymbols(id);
    }

    @PostMapping("/{id}/symbols")
    public ResponseEntity<Void> addSymbol(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.addSymbol(id, body.get("symbol"));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/symbols/{symbol}")
    public ResponseEntity<Void> removeSymbol(@PathVariable Long id, @PathVariable String symbol) {
        service.removeSymbol(id, symbol);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/import-csv")
    public Map<String, Object> importCsv(@PathVariable Long id,
                                          @RequestParam("file") MultipartFile file) throws Exception {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        int count = service.importCsv(id, content);
        return Map.of("imported", count);
    }
}
