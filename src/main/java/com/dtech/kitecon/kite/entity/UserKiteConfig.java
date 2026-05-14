package com.dtech.kitecon.kite.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "user_kite_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserKiteConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "platform_user_id", nullable = false)
    private Long platformUserId;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "api_key", nullable = false, length = 255)
    private String apiKey;

    @Column(name = "api_secret", nullable = false, length = 255)
    private String apiSecret;

    @Column(name = "kite_user_id", length = 100)
    private String kiteUserId;

    @Column(name = "access_token", columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "public_token", columnDefinition = "TEXT")
    private String publicToken;

    /** AES-encrypted Zerodha login password — null when auto-login isn't configured. */
    @Column(name = "zerodha_password_encrypted", length = 1024)
    private String zerodhaPasswordEncrypted;

    /** AES-encrypted base32 TOTP shared secret (RFC 6238). */
    @Column(name = "totp_secret_encrypted", length = 512)
    private String totpSecretEncrypted;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
