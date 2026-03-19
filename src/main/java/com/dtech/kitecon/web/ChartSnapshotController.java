package com.dtech.kitecon.web;

import com.dtech.kitecon.data.ChartSnapshot;
import com.dtech.kitecon.repository.ChartSnapshotRepository;
import com.dtech.kitecon.service.ChartSnapshotService;
import com.dtech.kitecon.service.SnapshotSummaryService;
import com.dtech.kitecon.service.ai.tools.ValidationResult;
import com.dtech.kitecon.service.model.SnapshotRequest;
import com.dtech.kitecon.service.model.SnapshotResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ChartSnapshotController - REST API for chart snapshots
 * Provides endpoints for creating, viewing, and managing snapshots
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/snapshots")
@CrossOrigin
@Slf4j
public class ChartSnapshotController {

    private final ChartSnapshotService snapshotService;
    private final ChartSnapshotRepository snapshotRepository;
    private final SnapshotSummaryService summaryService;

    /**
     * Create a new snapshot with validation
     * POST /api/snapshots/create
     */
    @PostMapping("/create")
    public ResponseEntity<SnapshotResult> createSnapshot(
        @RequestBody SnapshotRequest request,
        Authentication auth
    ) {
        try {
            String username = auth.getName();
            log.info("Creating snapshot for user: {}, symbol: {}", username, request.getSymbol());

            SnapshotResult result = snapshotService.createSnapshot(request, username);

            if (result.isSuccess()) {
                return ResponseEntity.ok(result);
            } else {
                return ResponseEntity.badRequest().body(result);
            }

        } catch (Exception e) {
            log.error("Error in createSnapshot endpoint", e);
            return ResponseEntity.status(500)
                .body(SnapshotResult.error("Server error: " + e.getMessage()));
        }
    }

    /**
     * Validate pattern without creating snapshot (preview)
     * POST /api/snapshots/validate
     */
    @PostMapping("/validate")
    public ResponseEntity<ValidationResult> validatePattern(
        @RequestBody SnapshotRequest request,
        Authentication auth
    ) {
        try {
            log.info("Validating pattern for user: {}, symbol: {}", auth.getName(), request.getSymbol());

            ValidationResult result = snapshotService.validateOnly(request);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error in validatePattern endpoint", e);
            return ResponseEntity.status(500).body(
                ValidationResult.builder()
                    .isValid(false)
                    .confidence(0.0)
                    .reason("Server error: " + e.getMessage())
                    .build()
            );
        }
    }

    /**
     * Get snapshot by ID
     * GET /api/snapshots/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ChartSnapshot> getSnapshot(
        @PathVariable Long id,
        Authentication auth
    ) {
        try {
            ChartSnapshot snapshot = snapshotService.getSnapshot(id, auth.getName());

            if (snapshot == null) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(snapshot);

        } catch (Exception e) {
            log.error("Error getting snapshot", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get user's own snapshots
     * GET /api/snapshots/my-snapshots?page=0&size=20
     */
    @GetMapping("/my-snapshots")
    public ResponseEntity<Page<ChartSnapshot>> getMySnapshots(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        Authentication auth
    ) {
        try {
            Pageable pageable = PageRequest.of(page, size);
            Page<ChartSnapshot> snapshots = snapshotRepository
                .findByUsernameOrderByCreatedAtDesc(auth.getName(), pageable);

            return ResponseEntity.ok(snapshots);

        } catch (Exception e) {
            log.error("Error getting user snapshots", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get public snapshots (social feed)
     * GET /api/snapshots/public?symbol=TCS&pattern=TRIANGLE&page=0&size=20
     */
    @GetMapping("/public")
    public ResponseEntity<Page<ChartSnapshot>> getPublicSnapshots(
        @RequestParam(required = false) String symbol,
        @RequestParam(required = false) String pattern,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        Authentication auth
    ) {
        try {
            Pageable pageable = PageRequest.of(page, size);
            Page<ChartSnapshot> snapshots;

            if (symbol != null && pattern != null) {
                snapshots = snapshotRepository.findBySymbolAndPatternTypeAndVisibilityOrderByCreatedAtDesc(
                    symbol, pattern, ChartSnapshot.SnapshotVisibility.PUBLIC, pageable
                );
            } else if (symbol != null) {
                snapshots = snapshotRepository.findBySymbolAndVisibilityOrderByCreatedAtDesc(
                    symbol, ChartSnapshot.SnapshotVisibility.PUBLIC, pageable
                );
            } else if (pattern != null) {
                snapshots = snapshotRepository.findByPatternTypeAndVisibilityOrderByCreatedAtDesc(
                    pattern, ChartSnapshot.SnapshotVisibility.PUBLIC, pageable
                );
            } else {
                snapshots = snapshotRepository.findByVisibilityOrderByCreatedAtDesc(
                    ChartSnapshot.SnapshotVisibility.PUBLIC, pageable
                );
            }

            return ResponseEntity.ok(snapshots);

        } catch (Exception e) {
            log.error("Error getting public snapshots", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get top snapshots by likes
     * GET /api/snapshots/trending?page=0&size=10
     */
    @GetMapping("/trending")
    public ResponseEntity<Page<ChartSnapshot>> getTrendingSnapshots(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        Authentication auth
    ) {
        try {
            Pageable pageable = PageRequest.of(page, size);
            Page<ChartSnapshot> snapshots = snapshotRepository
                .findByVisibilityOrderByLikesCountDesc(
                    ChartSnapshot.SnapshotVisibility.PUBLIC,
                    pageable
                );

            return ResponseEntity.ok(snapshots);

        } catch (Exception e) {
            log.error("Error getting trending snapshots", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Delete snapshot
     * DELETE /api/snapshots/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSnapshot(
        @PathVariable Long id,
        Authentication auth
    ) {
        try {
            boolean deleted = snapshotService.deleteSnapshot(id, auth.getName());

            if (deleted) {
                return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("Error deleting snapshot", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Update snapshot visibility
     * PUT /api/snapshots/{id}/visibility
     * Body: { "visibility": "public", "groupIds": "[1,2,3]" }
     */
    @PutMapping("/{id}/visibility")
    public ResponseEntity<?> updateVisibility(
        @PathVariable Long id,
        @RequestBody Map<String, Object> body,
        Authentication auth
    ) {
        try {
            String visibilityStr = (String) body.get("visibility");
            String groupIds = (String) body.get("groupIds");

            ChartSnapshot.SnapshotVisibility visibility =
                ChartSnapshot.SnapshotVisibility.valueOf(visibilityStr.toUpperCase());

            boolean updated = snapshotService.updateVisibility(
                id,
                auth.getName(),
                visibility,
                groupIds
            );

            if (updated) {
                return ResponseEntity.ok(Map.of(
                    "status", "updated",
                    "id", id,
                    "visibility", visibility
                ));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("Error updating visibility", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Clone a snapshot (create derivative with own perspective)
     * POST /api/snapshots/{id}/clone
     * Body: { "userComment": "My view on this...", "patternTags": ["..."] }
     */
    @PostMapping("/{id}/clone")
    public ResponseEntity<SnapshotResult> cloneSnapshot(
        @PathVariable Long id,
        @RequestBody Map<String, Object> body,
        Authentication auth
    ) {
        try {
            String username = auth.getName();

            ChartSnapshot original = snapshotRepository.findById(id).orElse(null);

            if (original == null) {
                return ResponseEntity.notFound().build();
            }

            log.info("User {} cloning snapshot {} from {}", username, id, original.getUsername());

            // Create clone request
            SnapshotRequest cloneRequest = new SnapshotRequest();
            cloneRequest.setSymbol(original.getSymbol());
            cloneRequest.setTimeframe(original.getTimeframe());
            cloneRequest.setChartStateJson(original.getChartStateJson());
            cloneRequest.setLayoutId(original.getLayoutId());
            cloneRequest.setStartDate(original.getStartDate());
            cloneRequest.setEndDate(original.getEndDate());

            // User provides their own comment and tags
            String userComment = (String) body.get("userComment");
            if (userComment == null || userComment.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(SnapshotResult.error("User comment required for clone"));
            }
            cloneRequest.setUserComment(userComment);

            @SuppressWarnings("unchecked")
            List<String> patternTags = (List<String>) body.get("patternTags");
            if (patternTags != null && !patternTags.isEmpty()) {
                cloneRequest.setPatternTags(patternTags);
            } else {
                // Use original tags as starting point
                cloneRequest.setPatternTags(original.getPatternTagsList());
            }

            String visibility = (String) body.getOrDefault("visibility", "PRIVATE");
            cloneRequest.setVisibility(ChartSnapshot.SnapshotVisibility.valueOf(visibility.toUpperCase()));

            Boolean performAiValidation = (Boolean) body.getOrDefault("performAiValidation", true);
            cloneRequest.setPerformAiValidation(performAiValidation);

            // Create the cloned snapshot
            SnapshotResult result = snapshotService.createSnapshot(cloneRequest, username);

            if (result.isSuccess() && result.getSnapshotId() != null) {
                // Update clone to reference parent
                ChartSnapshot clonedSnapshot = snapshotRepository.findById(result.getSnapshotId()).orElse(null);
                if (clonedSnapshot != null) {
                    clonedSnapshot.setParentSnapshotId(id);
                    snapshotRepository.save(clonedSnapshot);
                }

                // Increment parent's clone count
                original.setCloneCount(original.getCloneCount() + 1);
                snapshotRepository.save(original);

                log.info("Snapshot {} cloned as {}", id, result.getSnapshotId());
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error cloning snapshot", e);
            return ResponseEntity.status(500)
                .body(SnapshotResult.error("Failed to clone snapshot: " + e.getMessage()));
        }
    }

    /**
     * Like a snapshot
     * POST /api/snapshots/{id}/like
     */
    @PostMapping("/{id}/like")
    public ResponseEntity<?> likeSnapshot(
        @PathVariable Long id,
        Authentication auth
    ) {
        try {
            // TODO: Implement like/unlike with SnapshotLikeRepository
            // For now, just increment the counter

            ChartSnapshot snapshot = snapshotRepository.findById(id).orElse(null);

            if (snapshot == null) {
                return ResponseEntity.notFound().build();
            }

            snapshot.setLikesCount(snapshot.getLikesCount() + 1);
            snapshotRepository.save(snapshot);

            return ResponseEntity.ok(Map.of(
                "status", "liked",
                "id", id,
                "likesCount", snapshot.getLikesCount()
            ));

        } catch (Exception e) {
            log.error("Error liking snapshot", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get quick analysis summary for a symbol
     * GET /api/snapshots/summary?symbol=TCS&timeframe=1h
     */
    @GetMapping("/summary")
    public ResponseEntity<SnapshotSummaryService.SnapshotSummary> getSnapshotSummary(
        @RequestParam String symbol,
        @RequestParam(defaultValue = "1d") String timeframe,
        Authentication auth
    ) {
        try {
            log.info("Fetching snapshot summary for {} {}", symbol, timeframe);

            SnapshotSummaryService.SnapshotSummary summary =
                summaryService.getQuickSummary(symbol, timeframe);

            return ResponseEntity.ok(summary);

        } catch (Exception e) {
            log.error("Error fetching snapshot summary", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get snapshot statistics
     * GET /api/snapshots/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats(Authentication auth) {
        try {
            String username = auth.getName();

            long totalSnapshots = snapshotRepository.countByUsernameAndVisibility(
                username,
                ChartSnapshot.SnapshotVisibility.PRIVATE
            ) + snapshotRepository.countByUsernameAndVisibility(
                username,
                ChartSnapshot.SnapshotVisibility.PUBLIC
            );

            long publicSnapshots = snapshotRepository.countByUsernameAndVisibility(
                username,
                ChartSnapshot.SnapshotVisibility.PUBLIC
            );

            long privateSnapshots = snapshotRepository.countByUsernameAndVisibility(
                username,
                ChartSnapshot.SnapshotVisibility.PRIVATE
            );

            return ResponseEntity.ok(Map.of(
                "total", totalSnapshots,
                "public", publicSnapshots,
                "private", privateSnapshots
            ));

        } catch (Exception e) {
            log.error("Error getting stats", e);
            return ResponseEntity.status(500).build();
        }
    }
}
