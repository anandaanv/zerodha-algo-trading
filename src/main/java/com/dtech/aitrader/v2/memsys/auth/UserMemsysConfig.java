package com.dtech.aitrader.v2.memsys.auth;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Per-user memsys OAuth tokens. Mirrors the {@code user_kite_config} pattern.
 *
 * Populated by {@code GET /memsys/callback} after the Cognito-hosted Google OAuth
 * flow succeeds. Used by the per-user {@link com.dtech.aitrader.v2.memsys.MemsysClient}
 * factory to resolve the right bearer token + refresh-on-401.
 *
 * Tenant isolation: memsys decodes the JWT and resolves the tenant via tenant_identities
 * (RLS-enforced). Algotrade never passes user_id in tool args — the access_token is
 * sufficient identification. We still store the {@code memsysTenantSub} for audit so we
 * know which memsys tenant this algotrade user is bound to.
 */
@Entity
@Table(name = "user_memsys_config",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_memsys_user",
                columnNames = {"user_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMemsysConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Algotrade user id. One memsys connection per algotrade user. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Cognito 'sub' claim from the id_token — the memsys tenant identifier. */
    @Column(name = "memsys_tenant_sub", length = 128)
    private String memsysTenantSub;

    /** Optional display name pulled from id_token (email or name claim). */
    @Column(name = "display_name", length = 256)
    private String displayName;

    /** Encrypted access token (Cognito JWT). Short-lived (~1h by default). */
    @Lob
    @Column(name = "access_token_encrypted", columnDefinition = "TEXT")
    private String accessTokenEncrypted;

    /** Encrypted refresh token. Long-lived (default 30d on Cognito). */
    @Lob
    @Column(name = "refresh_token_encrypted", columnDefinition = "TEXT")
    private String refreshTokenEncrypted;

    /** Cognito client_id issued by DCR registration (per-user). Plaintext. */
    @Column(name = "client_id", length = 128)
    private String clientId;

    /** Cognito client_secret issued by DCR registration (per-user). Encrypted. */
    @Lob
    @Column(name = "client_secret_encrypted", columnDefinition = "TEXT")
    private String clientSecretEncrypted;

    /** When the access token expires (server-time). Used to proactively refresh before MCP calls. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Space-separated scopes granted at the time of issue (e.g. "memory.read memory.write"). */
    @Column(name = "scopes", length = 512)
    private String scopes;

    /** Whether this config is the active one for the user. (One-active-per-user is the unique key.) */
    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
