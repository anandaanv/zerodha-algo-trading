package com.dtech.aitrader.web;

import com.dtech.aitrader.data.AiTradeReplay;
import com.dtech.aitrader.repository.AiTradeReplayRepository;
import com.dtech.aitrader.service.AiTradeReplayService;
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
@RequestMapping("/api/ai-trader/replay")
@RequiredArgsConstructor
@Slf4j
public class AiTradeReplayController {
    private final AiTradeReplayService replayService;
    private final AiTradeReplayRepository replayRepository;
    private final UserRepository userRepository;

    public record RunRequest(Long simulationTradeId) {}

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody RunRequest req, Authentication auth) {
        try {
            Long userId = resolveUserId(auth);
            AiTradeReplay replay = replayService.replayTrade(req.simulationTradeId(), userId);
            return ResponseEntity.ok(replay);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Replay failed for sim trade {}", req.simulationTradeId(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<AiTradeReplay> list(@RequestParam(required = false) String symbol, Authentication auth) {
        if (symbol != null) return replayRepository.findBySymbolOrderByRequestedAtDesc(symbol);
        return replayRepository.findAll();
    }

    @GetMapping("/by-trade/{simTradeId}")
    public List<AiTradeReplay> byTrade(@PathVariable Long simTradeId, Authentication auth) {
        return replayRepository.findBySourceSimulationTradeId(simTradeId);
    }

    private Long resolveUserId(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
