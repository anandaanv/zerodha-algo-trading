package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_chart_state",
       uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "period", "layout_name"}))
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

    // Layout name (e.g. "default", "My Trading Setup")
    // Allows multiple saved layouts per symbol+period combination
    @Column(name = "layout_name", nullable = false)
    @Builder.Default
    private String layoutName = "default";

    // JSON payload containing drawings/line tools (TradingView drawings)
    // This is saved separately from chart layout as per TradingView best practices
    @Lob
    @Column(name = "overlays_json", columnDefinition = "TEXT")
    private String overlaysJson;

    // Chart layout metadata: indicators, chart settings, but NOT drawings
    // Drawings are stored in overlaysJson for per-symbol reusability
    @Lob
    @Column(name = "meta_json", columnDefinition = "TEXT")
    private String metaJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
