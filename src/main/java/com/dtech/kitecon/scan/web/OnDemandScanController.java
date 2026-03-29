package com.dtech.kitecon.scan.web;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.scan.entity.*;
import com.dtech.kitecon.scan.exception.RateLimitExceededException;
import com.dtech.kitecon.scan.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
@Slf4j
public class OnDemandScanController {

    private final OnDemandScanService scanService;
    private final ScanGroupService groupService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // --- Scan endpoints ---

    @PostMapping
    public ResponseEntity<?> initiateScan(Authentication auth, @RequestBody ScanRequest request) {
        try {
            Long userId = resolveUserId(auth);
            OnDemandScan scan = scanService.initiateScan(userId, request.getSymbol(), request.getPrimaryTimeframe());
            return ResponseEntity.ok(toDto(scan));
        } catch (RateLimitExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Scan initiation failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> listScans(Authentication auth) {
        Long userId = resolveUserId(auth);
        List<Map<String, Object>> scans = scanService.getVisibleScans(userId)
                .stream().map(this::toDto).toList();
        return ResponseEntity.ok(scans);
    }

    @GetMapping("/{scanId}")
    public ResponseEntity<?> getScan(@PathVariable Long scanId, Authentication auth) {
        return scanService.getScan(scanId)
                .map(s -> ResponseEntity.ok(toDto(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{scanId}/layouts")
    public ResponseEntity<?> getScanLayouts(@PathVariable Long scanId, Authentication auth) {
        List<Map<String, Object>> result = scanService.getScanLayouts(scanId).stream().map(layout -> {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", layout.getId());
            dto.put("scanId", layout.getScanId());
            dto.put("timeframe", layout.getTimeframe());
            dto.put("tabOrder", layout.getTabOrder());
            try {
                dto.put("overlays", layout.getOverlaysJson() != null
                        ? objectMapper.readValue(layout.getOverlaysJson(), Map.class)
                        : new LinkedHashMap<>());
            } catch (Exception e) {
                dto.put("overlays", new LinkedHashMap<>());
            }
            return dto;
        }).toList();
        return ResponseEntity.ok(result);
    }

    // --- Group management endpoints (admin) ---

    @PostMapping("/groups")
    public ResponseEntity<?> createGroup(Authentication auth, @RequestBody GroupRequest request) {
        var group = groupService.createGroup(request.getName(), request.getMaxScansPerHour());
        return ResponseEntity.ok(group);
    }

    @GetMapping("/groups")
    public ResponseEntity<?> listGroups(Authentication auth) {
        return ResponseEntity.ok(groupService.listGroups());
    }

    @PostMapping("/groups/{groupId}/members")
    public ResponseEntity<?> addMember(@PathVariable Long groupId, @RequestBody MemberRequest request, Authentication auth) {
        groupService.addMember(groupId, request.getUserId());
        return ResponseEntity.ok(Map.of("status", "added"));
    }

    @DeleteMapping("/groups/{groupId}/members/{userId}")
    public ResponseEntity<?> removeMember(@PathVariable Long groupId, @PathVariable Long userId, Authentication auth) {
        groupService.removeMember(groupId, userId);
        return ResponseEntity.ok(Map.of("status", "removed"));
    }

    @GetMapping("/groups/{groupId}/members")
    public ResponseEntity<?> getMembers(@PathVariable Long groupId, Authentication auth) {
        return ResponseEntity.ok(groupService.getMembers(groupId));
    }

    // --- Helpers ---

    private Map<String, Object> toDto(OnDemandScan scan) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", scan.getId());
        dto.put("userId", scan.getUserId());
        dto.put("symbol", scan.getSymbol());
        dto.put("primaryTimeframe", scan.getPrimaryTimeframe());
        dto.put("allTimeframes", scan.getAllTimeframes());
        dto.put("status", scan.getStatus());
        dto.put("requestedAt", scan.getRequestedAt());
        dto.put("completedAt", scan.getCompletedAt());
        dto.put("errorMessage", scan.getErrorMessage());
        // Parse resultJson to object if available
        if (scan.getResultJson() != null) {
            try {
                dto.put("result", objectMapper.readValue(scan.getResultJson(), Map.class));
            } catch (Exception e) {
                dto.put("result", null);
            }
        } else {
            dto.put("result", null);
        }
        return dto;
    }

    private Long resolveUserId(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + auth.getName()));
    }

    // Request DTOs (static inner classes)
    @lombok.Data
    public static class ScanRequest {
        private String symbol;
        private String primaryTimeframe;
    }

    @lombok.Data
    public static class GroupRequest {
        private String name;
        private Integer maxScansPerHour;
    }

    @lombok.Data
    public static class MemberRequest {
        private Long userId;
    }
}
