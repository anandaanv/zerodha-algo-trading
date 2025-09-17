package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "subscription")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Only NSE tradingsymbols should be stored here (validated on insert)
    @Column(name = "trading_symbol", nullable = false, unique = true)
    private String tradingSymbol;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // When this subscription was last refreshed
    @Column(name = "last_updated_at", nullable = false)
    private LocalDateTime lastUpdatedAt;

    // Keep a "latest" data timestamp for incremental fetches (nullable).
    // Next update will fetch data from this timestamp forward; if null, HistoricalDateLimit is used.
    @Column(name = "latest_timestamp", nullable = true)
    private Instant latestTimestamp;

    // Simple status: ACTIVE / PAUSED / DELETED
    @Column(name = "status", nullable = false)
    private String status;

    // Optional metadata (json) for caching resolved instrument tokens etc.
    @Lob
    @Column(name = "meta_json", columnDefinition = "TEXT")
    private String metaJson;
}
