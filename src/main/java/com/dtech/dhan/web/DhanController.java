package com.dtech.dhan.web;

import com.dtech.dhan.config.DhanConnectConfig;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for Dhan API operations
 * Handles authentication token updates and status checks
 */
@RestController
@RequestMapping("/api/dhan")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class DhanController {

    private final DhanConnectConfig dhanConnectConfig;

    /**
     * Get Dhan configuration status
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        try {
            boolean configured = dhanConnectConfig.isConfigured();
            String clientId = dhanConnectConfig.getClientId();
            String userId = dhanConnectConfig.getUserId();

            DhanStatusResponse response = new DhanStatusResponse();
            response.setConfigured(configured);
            response.setClientId(clientId != null ? maskString(clientId) : null);
            response.setUserId(userId);
            response.setHasAccessToken(dhanConnectConfig.getAccessToken() != null
                && !dhanConnectConfig.getAccessToken().trim().isEmpty());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting Dhan status", e);
            return ResponseEntity.internalServerError().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Update Dhan access token
     * Requires ADMIN role
     */
    @PostMapping("/update-token")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateAccessToken(@RequestBody UpdateTokenRequest request) {
        try {
            if (request.getAccessToken() == null || request.getAccessToken().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(new ErrorResponse("Access token is required"));
            }

            if (request.getUserId() == null || request.getUserId().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(new ErrorResponse("User ID is required"));
            }

            dhanConnectConfig.updateAccessToken(request.getAccessToken(), request.getUserId());

            log.info("Dhan access token updated successfully for user: {}", request.getUserId());

            return ResponseEntity.ok(new SuccessResponse("Access token updated successfully"));
        } catch (Exception e) {
            log.error("Error updating Dhan access token", e);
            return ResponseEntity.internalServerError().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Test Dhan API connection
     */
    @GetMapping("/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> testConnection() {
        try {
            if (!dhanConnectConfig.isConfigured()) {
                return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Dhan API not configured. Please set access token first."));
            }

            // TODO: Add actual API test call (e.g., fetch user profile or account info)
            // For now, just check if token exists

            return ResponseEntity.ok(new SuccessResponse("Dhan API configured successfully"));
        } catch (Exception e) {
            log.error("Error testing Dhan connection", e);
            return ResponseEntity.internalServerError().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Mask sensitive string for display (show first 4 and last 4 chars)
     */
    private String maskString(String input) {
        if (input == null || input.length() <= 8) {
            return "****";
        }
        return input.substring(0, 4) + "****" + input.substring(input.length() - 4);
    }

    // DTOs

    @Data
    public static class DhanStatusResponse {
        private boolean configured;
        private String clientId;
        private String userId;
        private boolean hasAccessToken;
    }

    @Data
    public static class UpdateTokenRequest {
        private String accessToken;
        private String userId;
    }

    @Data
    public static class SuccessResponse {
        private final String message;
    }

    @Data
    public static class ErrorResponse {
        private final String error;
    }
}
