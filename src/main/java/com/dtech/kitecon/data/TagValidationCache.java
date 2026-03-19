package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * TagValidationCache - Caches AI-validated pattern tags
 * Reduces API calls by storing validated tag mappings
 */
@Entity
@Table(name = "tag_validation_cache", indexes = {
    @Index(name = "idx_normalized_tag", columnList = "normalizedTag")
})
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TagValidationCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Tag as entered by user (may have typos, different casing)
     */
    @Column(name = "original_tag", nullable = false, unique = true)
    private String originalTag;

    /**
     * Corrected/normalized tag
     */
    @Column(name = "normalized_tag", nullable = false)
    private String normalizedTag;

    /**
     * Number of times this tag mapping was used
     */
    @Column(name = "validation_count")
    @Builder.Default
    private Integer validationCount = 1;

    /**
     * AI confidence in the normalization (0.0 to 1.0)
     */
    @Column(name = "confidence")
    @Builder.Default
    private Double confidence = 1.0;

    /**
     * When this cache entry was first created
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * When this cache entry was last used
     */
    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastUsedAt == null) {
            lastUsedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastUsedAt = LocalDateTime.now();
    }
}
