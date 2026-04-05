package com.dtech.wavelab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "wl_overlay", indexes = {
    @Index(name = "idx_symbol_timeframe", columnList = "symbol, timeframe")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WlOverlay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(nullable = false, length = 16)
    private String timeframe;

    @Column(nullable = false, length = 64)
    private String algoName;

    @Lob
    @Column(nullable = false)
    private String drawingJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = Instant.now();
    }
}
