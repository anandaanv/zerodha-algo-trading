package com.dtech.aitrader.annotation.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "chart_drawing_annotation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_drawing_user_tab_symbol_drawing",
                columnNames = {"user_id", "tab_uuid", "symbol", "drawing_id"}))
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChartDrawingAnnotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tab_uuid", nullable = false, length = 64)
    private String tabUuid;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "interval_name", length = 16)
    private String interval;

    @Column(name = "drawing_id", nullable = false, length = 64)
    private String drawingId;

    @Column(name = "intent", nullable = false, length = 32)
    private String intent;

    @Lob
    @Column(name = "intent_params_json", columnDefinition = "TEXT")
    private String intentParamsJson;

    @Lob
    @Column(name = "geometry_json", columnDefinition = "TEXT")
    private String geometryJson;

    @Lob
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "weight", nullable = false)
    private Integer weight;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (weight == null) weight = 3;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
