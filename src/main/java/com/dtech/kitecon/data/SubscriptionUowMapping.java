package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Many-to-many mapping table between Subscription and SubscriptionUow.
 * Tracks which subscriptions require which UOWs.
 */
@Entity
@Table(name = "subscription_uow_mapping",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_sub_uow_mapping", columnNames = {"subscription_id", "subscription_uow_id"})
        },
        indexes = {
                @Index(name = "idx_mapping_subscription", columnList = "subscription_id"),
                @Index(name = "idx_mapping_uow", columnList = "subscription_uow_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionUowMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_mapping_subscription"))
    private Subscription subscription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_uow_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_mapping_uow"))
    private SubscriptionUow subscriptionUow;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
