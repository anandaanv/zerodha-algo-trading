package com.dtech.kitecon.data;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.enums.SubscriptionUowStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "subscription_uow",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_sub_uow_parent_symbol_timeframe", columnNames = {"parent_subscription_id", "trading_symbol", "timeframe"})
        },
        indexes = {
                @Index(name = "idx_sub_uow_parent", columnList = "parent_subscription_id"),
                @Index(name = "idx_sub_uow_status", columnList = "status"),
                @Index(name = "idx_sub_uow_status_updated", columnList = "status, last_updated_at"),
                @Index(name = "idx_sub_uow_due", columnList = "status, next_run_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionUow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Parent subscription
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_subscription_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_sub_uow_parent"))
    private Subscription parentSubscription;

    @Column(name = "trading_symbol", nullable = false, length = 128)
    private String tradingSymbol;

    // NSE/NFO/etc, captured when scheduling
    @Column(name = "exchange", length = 16)
    private String exchange;

    // Human-friendly tag like SPOT, FUT, CE1, CE-1, PE1, etc.
    @Column(name = "instrument_tag", length = 32)
    private String instrumentTag;

    @Enumerated(EnumType.STRING)
    @Column(name = "timeframe", nullable = false, length = 32)
    private Interval interval;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SubscriptionUowStatus status;

    @Column(name = "last_traded_price", precision = 18, scale = 6)
    private BigDecimal lastTradedPrice;

    // Progress marker for incremental updates
    @Column(name = "latest_timestamp")
    private Instant latestTimestamp;

    @Column(name = "last_updated_at")
    private Instant lastUpdatedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    // Next time this UOW should be processed
    @Column(name = "next_run_at")
    private Instant nextRunAt;

    // Optimistic locking
    @Version
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
