package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * StockAnalysisCache - Caches external API data to reduce rate limits and improve performance
 * Stores fundamentals, news, correlation, and social sentiment data
 */
@Entity
@Table(name = "stock_analysis_cache", indexes = {
    @Index(name = "idx_symbol_type", columnList = "symbol,analysis_type"),
    @Index(name = "idx_expires_at", columnList = "expires_at")
})
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockAnalysisCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Trading symbol (e.g., TCS, INFY)
     */
    @Column(nullable = false, length = 100)
    private String symbol;

    /**
     * Timeframe (optional, for timeframe-specific analysis)
     */
    @Column(length = 20)
    private String timeframe;

    /**
     * Type of analysis: fundamentals, news, correlation, social
     */
    @Column(name = "analysis_type", nullable = false, length = 50)
    private String analysisType;

    /**
     * Analysis data stored as JSON
     */
    @Column(name = "analysis_data", columnDefinition = "TEXT")
    private String analysisData;

    /**
     * When this cache entry was generated
     */
    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    /**
     * When this cache entry expires
     * Different TTLs for different types:
     * - fundamentals: 24 hours
     * - news: 15 minutes
     * - correlation: 1 hour
     * - social: 30 minutes
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        if (generatedAt == null) {
            generatedAt = LocalDateTime.now();
        }
    }

    /**
     * Check if this cache entry is still valid
     */
    public boolean isValid() {
        return expiresAt != null && LocalDateTime.now().isBefore(expiresAt);
    }
}
