package com.dtech.kitecon.trade.entity;

import com.dtech.kitecon.trade.enums.TradingSegment;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "segment_config", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"symbol", "segment"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TradingSegment segment;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal capitalPct;

    @Column(length = 20)
    private String provider;

    @Column(name = "order_product", length = 10)
    private String orderProduct;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @PrePersist
    void prePersist() {
        if (provider == null) {
            provider = "KITE";
        }
        if (orderProduct == null) {
            orderProduct = "MTF";
        }
    }
}
