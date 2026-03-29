package com.dtech.kitecon.screener.elliott.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "suggestion_chart_layout",
    uniqueConstraints = @UniqueConstraint(columnNames = {"suggestion_id", "timeframe"}),
    indexes = @Index(name = "idx_scl_suggestion_id", columnList = "suggestion_id")
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestionChartLayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "suggestion_id", nullable = false)
    private Long suggestionId;

    @Column(length = 64, nullable = false)
    private String timeframe;

    @Column(name = "tab_order", nullable = false)
    private int tabOrder;

    @Lob
    @Column(name = "overlays_json", columnDefinition = "MEDIUMTEXT")
    private String overlaysJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = Instant.now();
    }
}
