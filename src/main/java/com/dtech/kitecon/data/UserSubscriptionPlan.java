package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * UserSubscriptionPlan - Manages user subscription tiers and feature access
 * Controls limits on private snapshots, group sharing, etc.
 */
@Entity
@Table(name = "user_subscription_plan", indexes = {
    @Index(name = "idx_username", columnList = "username", unique = true)
})
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserSubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Username (unique)
     */
    @Column(nullable = false, unique = true)
    private String username;

    /**
     * Subscription plan type
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false)
    @Builder.Default
    private PlanType planType = PlanType.FREE;

    /**
     * Maximum number of private snapshots allowed
     * Free: 10, Basic: 100, Premium: unlimited (-1)
     */
    @Column(name = "private_snapshots_limit")
    @Builder.Default
    private Integer privateSnapshotsLimit = 10;

    /**
     * Whether group sharing is enabled
     * Free: false, Basic: true, Premium: true
     */
    @Column(name = "group_sharing_enabled")
    @Builder.Default
    private Boolean groupSharingEnabled = false;

    /**
     * Additional features as JSON
     * Can include: ai_analysis_limit, export_enabled, etc.
     */
    @Lob
    @Column(name = "features_json", columnDefinition = "JSON")
    private String featuresJson;

    /**
     * Subscription valid until (null for free tier)
     */
    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    /**
     * Timestamp when created
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when last updated
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Check if subscription is currently active
     */
    public boolean isActive() {
        return validUntil == null || LocalDateTime.now().isBefore(validUntil);
    }

    public enum PlanType {
        FREE, BASIC, PREMIUM
    }
}
