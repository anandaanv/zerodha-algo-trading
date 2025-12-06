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

    @Column(nullable = false)
    private String symbol;

    @Column(name = "layout_id")
    private Long layoutId;

    @Lob
    @Column(name = "overlays_json", columnDefinition = "TEXT")
    private String overlaysJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        createdAt = LocalDateTime.now();
    }
}
