package com.dtech.kitecon.web;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Controller to expose remote sync configuration (ADMIN only).
 */
@RestController
@RequestMapping("/api/remote-sync")
@CrossOrigin(origins = "*")
public class RemoteSyncController {

    @Value("${remote.sync.api.url:}")
    private String remoteSyncApiUrl;

    @Value("${remote.sync.username:}")
    private String remoteSyncUsername;

    @Value("${remote.sync.password:}")
    private String remoteSyncPassword;

    /**
     * Get remote sync configuration (ADMIN only).
     * GET /api/remote-sync/config
     */
    @GetMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RemoteSyncConfig> getConfig() {
        return ResponseEntity.ok(new RemoteSyncConfig(
                remoteSyncApiUrl,
                remoteSyncUsername,
                remoteSyncPassword
        ));
    }

    @Data
    public static class RemoteSyncConfig {
        private final String apiUrl;
        private final String username;
        private final String password;
    }
}
