package com.dtech.algo.screener.db;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "screeners")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScreenerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optional logical name for identification
    @Column(length = 255, unique = true)
    private String name;

    // Kotlin screener code
    @Lob
    @Column(name = "script", columnDefinition = "TEXT")
    private String script;

    // JSON for screener config (mapping + workflow)
    @Lob
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    // JSON array of subscribed symbols (e.g., ["NIFTY","BANKNIFTY"])
    @Lob
    @Column(name = "symbols_json", columnDefinition = "TEXT")
    private String symbolsJson;

    // Default timeframe subscribed (e.g., "15min") - can be overridden per run
    @Column(length = 64)
    private String timeframe;

    // OpenAI prompt JSON (object)
    @Lob
    @Column(name = "prompt_json", columnDefinition = "TEXT")
    private String promptJson;

    // Optional prompt identifier; if provided, it takes precedence over promptJson
    @Column(name = "prompt_id", length = 255)
    private String promptId;

    // JSON array declaring which charts (aliases) should be sent to OpenAI
    @Lob
    @Column(name = "charts_json", columnDefinition = "TEXT")
    private String chartsJson;

    // If true, (re)compile and register script before execution
    @Builder.Default
    private Boolean dirty = false;

    @Builder.Default
    private Boolean deleted = false;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (deleted == null) deleted = false;
        if (dirty == null) dirty = false;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
