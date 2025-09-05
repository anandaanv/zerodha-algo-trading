package com.dtech.drawings.entity;

import com.dtech.drawings.model.DrawingType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(name = "chart_drawings", indexes = {
        @Index(name = "idx_drawings_symbol_tf", columnList = "symbol,timeframe"),
        @Index(name = "idx_drawings_updated_at", columnList = "updatedAt")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DrawingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // e.g., "NSE:TCS" or "BTCUSDT"
    @Column(nullable = false, length = 64)
    private String symbol;

    // e.g., "1m","5m","15m","1h","1d"
    @Column(nullable = false, length = 16)
    private String timeframe;

    // Optional: multi-tenant/user association (nullable)
    @Column(length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DrawingType type;

    // Optional human label
    @Column(length = 128)
    private String name;

    // Arbitrary JSON payload representing points, style, etc.
    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
