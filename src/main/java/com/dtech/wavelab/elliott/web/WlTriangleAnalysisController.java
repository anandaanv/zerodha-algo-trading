package com.dtech.wavelab.elliott.web;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.wavelab.elliott.dto.TriangleAnalyzeRequest;
import com.dtech.wavelab.elliott.dto.TriangleRunResponse;
import com.dtech.wavelab.elliott.service.WlTriangleAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/wave-lab/elliott/triangles")
@CrossOrigin
public class WlTriangleAnalysisController {

    private final WlTriangleAnalysisService triangleAnalysisService;
    private final UserRepository userRepository;

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody TriangleAnalyzeRequest request, Authentication auth) {
        try {
            Long userId = resolveUserId(auth);
            TriangleRunResponse response = triangleAnalysisService.analyze(userId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Error analyzing triangle", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error analyzing triangle", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<?> getRun(@PathVariable Long id, Authentication auth) {
        try {
            Long userId = resolveUserId(auth);
            TriangleRunResponse response = triangleAnalysisService.getRun(userId, id);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Long resolveUserId(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new IllegalArgumentException("Unauthorized");
        }
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + auth.getName()));
        return user.getId();
    }
}
