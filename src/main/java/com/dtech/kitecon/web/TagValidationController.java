package com.dtech.kitecon.web;

import com.dtech.kitecon.service.TagValidationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * TagValidationController - REST API for tag validation
 * Provides endpoints for validating and normalizing pattern tags
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tags")
@CrossOrigin
@Slf4j
public class TagValidationController {

    private final TagValidationService tagValidationService;

    /**
     * Validate and normalize a list of tags
     * POST /api/tags/validate
     * Body: { "tags": ["head and sholder", "tripple top", ...] }
     */
    @PostMapping("/validate")
    public ResponseEntity<TagValidationResponse> validateTags(
        @RequestBody TagValidationRequest request,
        Authentication auth
    ) {
        try {
            log.info("User {} validating tags: {}", auth.getName(), request.getTags());

            List<String> normalizedTags = tagValidationService.validateAndNormalizeTags(request.getTags());

            return ResponseEntity.ok(TagValidationResponse.builder()
                .originalTags(request.getTags())
                .normalizedTags(normalizedTags)
                .success(true)
                .build());

        } catch (Exception e) {
            log.error("Error validating tags", e);
            return ResponseEntity.status(500)
                .body(TagValidationResponse.builder()
                    .success(false)
                    .error("Tag validation failed: " + e.getMessage())
                    .build());
        }
    }

    /**
     * Validate a single tag
     * GET /api/tags/validate?tag=head%20and%20sholder
     */
    @GetMapping("/validate")
    public ResponseEntity<Map<String, String>> validateSingleTag(
        @RequestParam String tag,
        Authentication auth
    ) {
        try {
            String normalized = tagValidationService.validateTag(tag);

            return ResponseEntity.ok(Map.of(
                "original", tag,
                "normalized", normalized
            ));

        } catch (Exception e) {
            log.error("Error validating single tag", e);
            return ResponseEntity.status(500)
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get popular/suggested tags
     * GET /api/tags/popular
     */
    @GetMapping("/popular")
    public ResponseEntity<List<String>> getPopularTags(Authentication auth) {
        try {
            List<String> popularTags = tagValidationService.getPopularTags();
            return ResponseEntity.ok(popularTags);
        } catch (Exception e) {
            log.error("Error fetching popular tags", e);
            return ResponseEntity.status(500).body(List.of());
        }
    }

    // ============ Request/Response DTOs ============

    @Data
    public static class TagValidationRequest {
        private List<String> tags;
    }

    @Data
    @lombok.Builder
    public static class TagValidationResponse {
        private List<String> originalTags;
        private List<String> normalizedTags;
        private boolean success;
        private String error;
    }
}
