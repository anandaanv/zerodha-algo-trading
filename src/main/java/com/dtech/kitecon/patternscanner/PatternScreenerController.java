package com.dtech.kitecon.patternscanner;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pattern-screener")
@RequiredArgsConstructor
@Slf4j
public class PatternScreenerController {

    private final PatternScreenerService service;
    private final PatternScreenerRunRepository runRepository;
    private final PatternScreenerRunResultRepository resultRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        return ResponseEntity.ok(service.listScreeners(resolveUserId(auth)));
    }

    @PostMapping
    public ResponseEntity<?> create(Authentication auth, @RequestBody PatternScreenerRequest req) {
        return ResponseEntity.ok(service.createScreener(resolveUserId(auth), req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(Authentication auth, @PathVariable Long id, @RequestBody PatternScreenerRequest req) {
        return ResponseEntity.ok(service.updateScreener(resolveUserId(auth), id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(Authentication auth, @PathVariable Long id) {
        service.deleteScreener(resolveUserId(auth), id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> triggerRun(Authentication auth, @PathVariable Long id) {
        service.triggerAsync(id);
        return ResponseEntity.ok(Map.of("status", "started"));
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<?> getRuns(@PathVariable Long id) {
        return ResponseEntity.ok(runRepository.findTop10ByScreenerIdOrderByStartedAtDesc(id));
    }

    @GetMapping("/{id}/runs/{runId}/results")
    public ResponseEntity<?> getRunResults(@PathVariable Long id, @PathVariable Long runId) {
        return ResponseEntity.ok(resultRepository.findByRunIdOrderByScannedAt(runId));
    }

    private Long resolveUserId(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + auth.getName()));
    }
}
