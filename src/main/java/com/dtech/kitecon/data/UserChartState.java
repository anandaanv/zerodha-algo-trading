package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_chart_state")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserChartState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Chart symbol (e.g. "TCS")
    @Column(nullable = false)
    private String symbol;

    // UI period key (e.g. "1h")
    @Column(nullable = false)
    private String period;

    // JSON payload containing overlays keyed by plugin key
    @Lob
    @Column(name = "overlays_json", columnDefinition = "TEXT")
    private String overlaysJson;

    // Optional metadata stored as JSON (can be null)
    @Lob
    @Column(name = "meta_json", columnDefinition = "TEXT")
    private String metaJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
