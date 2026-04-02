package com.dtech.kitecon.data.copilot;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Stores per-user OpenAI API credentials.
 * The API key is encrypted at rest using AES with a server-side secret.
 */
@Entity
@Table(name = "copilot_user_openai_credential",
        uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserOpenAiCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** AES-encrypted OpenAI API key */
    @Column(name = "api_key_encrypted", nullable = true, length = 1024)
    private String apiKeyEncrypted;

    /** Optional: user can override the model (defaults to gpt-4.1-mini) */
    @Column(name = "model", length = 100)
    @Builder.Default
    private String model = "gpt-4.1-mini";

    /** Optional: user can override the base URL (for Azure OpenAI etc.) */
    @Column(name = "base_url", length = 255)
    @Builder.Default
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * When true, this is a local LLM endpoint (llama.cpp, Ollama, LM Studio, vLLM etc).
     * Local providers: use Chat Completions API, no real API key required.
     */
    @Column(name = "local_provider", nullable = false)
    @Builder.Default
    private boolean localProvider = false;

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
