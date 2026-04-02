package com.dtech.kitecon.data.copilot;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Stores one AI provider configuration per record.
 * A user can have multiple providers; exactly one has active=true.
 */
@Entity
@Table(name = "user_ai_provider",
        indexes = @Index(name = "idx_user_ai_provider_user", columnList = "user_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAiProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** User-defined label, e.g. "My GPT-4o", "Local Qwen" */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Base URL of the provider, e.g. https://api.openai.com/v1 or http://localhost:8080/v1 */
    @Column(name = "base_url", nullable = false, length = 255)
    private String baseUrl;

    /** Model name, e.g. gpt-4.1-mini or qwen3-coder-30b */
    @Column(name = "model", nullable = false, length = 100)
    private String model;

    /** AES-encrypted API key — null for local providers */
    @Column(name = "api_key_encrypted", length = 1024)
    private String apiKeyEncrypted;

    /**
     * When true, uses Chat Completions API (/v1/chat/completions).
     * When false, uses OpenAI Responses API (/v1/responses).
     */
    @Column(name = "local_provider", nullable = false)
    @Builder.Default
    private boolean localProvider = false;

    /** Only one provider per user can be active at a time. */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
