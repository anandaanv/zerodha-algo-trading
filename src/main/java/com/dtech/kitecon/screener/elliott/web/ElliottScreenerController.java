package com.dtech.kitecon.screener.elliott.web;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.screener.elliott.dto.*;
import com.dtech.kitecon.screener.elliott.repository.ElliottScreenerRunRepository;
import com.dtech.kitecon.screener.elliott.service.ElliottScreenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/elliott-screener")
@RequiredArgsConstructor
@Slf4j
public class ElliottScreenerController {

    private final ElliottScreenerService screenerService;
    private final ElliottScreenerRunRepository runRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(screenerService.listScreeners(userId));
    }

    @PostMapping
    public ResponseEntity<?> create(Authentication auth, @RequestBody ElliottScreenerRequest request) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(screenerService.createScreener(userId, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(Authentication auth, @PathVariable Long id) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(screenerService.getScreener(userId, id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(Authentication auth, @PathVariable Long id,
                                     @RequestBody ElliottScreenerRequest request) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(screenerService.updateScreener(userId, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(Authentication auth, @PathVariable Long id) {
        Long userId = resolveUserId(auth);
        screenerService.deleteScreener(userId, id);
        return ResponseEntity.ok(Map.of("status", "disabled"));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> triggerRun(Authentication auth, @PathVariable Long id) {
        Long userId = resolveUserId(auth);
        ElliottScreenerRunResponse response = screenerService.triggerNow(userId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<?> getRuns(Authentication auth, @PathVariable Long id) {
        Long userId = resolveUserId(auth);
        List<ElliottScreenerRunResponse> runs = runRepository
                .findTop10ByScreenerIdOrderByStartedAtDesc(id)
                .stream()
                .map(ElliottScreenerRunResponse::from)
                .toList();
        return ResponseEntity.ok(runs);
    }

    private Long resolveUserId(Authentication auth) {
        String username = auth.getName();
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + username));
    }
}
