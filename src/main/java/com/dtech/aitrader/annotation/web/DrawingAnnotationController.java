package com.dtech.aitrader.annotation.web;

import com.dtech.aitrader.annotation.dto.AnnotationBundleDto;
import com.dtech.aitrader.annotation.dto.DrawingAnnotationDto;
import com.dtech.aitrader.annotation.dto.JournalNoteDto;
import com.dtech.aitrader.annotation.dto.SaveAnnotationRequest;
import com.dtech.aitrader.annotation.dto.SaveJournalNoteRequest;
import com.dtech.aitrader.annotation.service.AnnotationBundleBuilder;
import com.dtech.aitrader.annotation.service.AnnotationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai-trader/annotations")
@RequiredArgsConstructor
@Slf4j
public class DrawingAnnotationController {

    private final AnnotationService service;
    private final AnnotationBundleBuilder bundleBuilder;

    // ── Drawing annotations ─────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<DrawingAnnotationDto>> list(
            @RequestParam String symbol,
            @RequestParam(required = false) String tabUuid,
            Authentication auth) {
        Long userId = extractUserId(auth);
        List<DrawingAnnotationDto> result = (tabUuid == null || tabUuid.isBlank())
                ? service.listForSymbol(userId, symbol)
                : service.listForChart(userId, symbol, tabUuid);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/bundle")
    public ResponseEntity<AnnotationBundleDto> bundle(
            @RequestParam String symbol,
            @RequestParam(required = false) String tabUuid,
            Authentication auth) {
        Long userId = extractUserId(auth);
        AnnotationBundleDto b = (tabUuid == null || tabUuid.isBlank())
                ? bundleBuilder.buildForPattern(userId, symbol)
                : bundleBuilder.buildForLevels(userId, symbol, tabUuid);
        return ResponseEntity.ok(b);
    }

    @PostMapping
    public ResponseEntity<DrawingAnnotationDto> save(
            @RequestBody SaveAnnotationRequest req,
            Authentication auth) {
        Long userId = extractUserId(auth);
        return ResponseEntity.ok(service.saveAnnotation(userId, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication auth) {
        Long userId = extractUserId(auth);
        try {
            service.deleteAnnotation(userId, id);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
    }

    // ── Journal notes (replaces the old structured /thesis endpoints) ───

    @GetMapping("/journal")
    public ResponseEntity<List<JournalNoteDto>> listJournal(
            @RequestParam String symbol,
            Authentication auth) {
        Long userId = extractUserId(auth);
        return ResponseEntity.ok(service.listJournalNotes(userId, symbol));
    }

    @PostMapping("/journal")
    public ResponseEntity<JournalNoteDto> addJournal(
            @RequestBody SaveJournalNoteRequest req,
            Authentication auth) {
        Long userId = extractUserId(auth);
        return ResponseEntity.ok(service.addJournalNote(userId, req));
    }

    @DeleteMapping("/journal/{id}")
    public ResponseEntity<?> deleteJournal(@PathVariable Long id, Authentication auth) {
        Long userId = extractUserId(auth);
        try {
            service.deleteJournalNote(userId, id);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
    }

    private Long extractUserId(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("User not authenticated");
        }
        // Matches the pattern used by AiLevelsController until UserRepository integration lands.
        return 1L;
    }
}
