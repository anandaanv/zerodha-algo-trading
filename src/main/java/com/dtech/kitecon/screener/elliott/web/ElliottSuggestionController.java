package com.dtech.kitecon.screener.elliott.web;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.screener.elliott.entity.SuggestionState;
import com.dtech.kitecon.screener.elliott.service.ElliottTradeSuggestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/elliott-suggestions")
@RequiredArgsConstructor
@Slf4j
public class ElliottSuggestionController {

    private final ElliottTradeSuggestionService suggestionService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> list(Authentication auth, @RequestParam(required = false) String states) {
        Long userId = resolveUserId(auth);
        List<SuggestionState> stateFilter = new ArrayList<>();
        if (states != null && !states.isBlank()) {
            for (String s : states.split(",")) {
                try { stateFilter.add(SuggestionState.valueOf(s.trim().toUpperCase())); }
                catch (IllegalArgumentException ignored) {}
            }
        }
        return ResponseEntity.ok(suggestionService.listForUser(userId, stateFilter));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(Authentication auth, @PathVariable Long id) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(suggestionService.get(userId, id));
    }

    @GetMapping("/screener/{screenerId}")
    public ResponseEntity<?> listForScreener(Authentication auth, @PathVariable Long screenerId) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(suggestionService.listForScreener(userId, screenerId));
    }

    @PostMapping("/from-scan/{scanId}")
    public ResponseEntity<?> createFromScan(Authentication auth, @PathVariable Long scanId) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(suggestionService.createFromScan(scanId, userId));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<?> accept(Authentication auth, @PathVariable Long id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        Long userId = resolveUserId(auth);
        String notes = body != null ? (String) body.get("notes") : null;
        return ResponseEntity.ok(suggestionService.accept(userId, id, notes));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activate(Authentication auth, @PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        Long userId = resolveUserId(auth);
        String notes = body != null ? (String) body.get("notes") : null;
        return ResponseEntity.ok(suggestionService.activate(userId, id, notes));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<?> close(Authentication auth, @PathVariable Long id,
                                    @RequestBody Map<String, Object> body) {
        Long userId = resolveUserId(auth);
        boolean successful = Boolean.parseBoolean(body.getOrDefault("successful", "false").toString());
        String notes = (String) body.getOrDefault("notes", null);
        return ResponseEntity.ok(suggestionService.close(userId, id, successful, notes));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(Authentication auth, @PathVariable Long id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        Long userId = resolveUserId(auth);
        String notes = body != null ? (String) body.get("notes") : null;
        return ResponseEntity.ok(suggestionService.reject(userId, id, notes));
    }

    private Long resolveUserId(Authentication auth) {
        String username = auth.getName();
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + username));
    }
}
