package com.dtech.kitecon.service.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Complete stock analysis response including all analysis types
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockAnalysisResponse {
    private String symbol;
    private String timeframe;
    private FundamentalsData fundamentals;
    private List<NewsItem> news;
    private CorrelationData correlation;
    private SocialSentimentData socialSentiment;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FundamentalsData {
        private Double peRatio;
        private Double marketCap;
        private Double epsValue;
        private Double dividendYield;
        private String sector;
        private String industry;
        private Double yearHigh;
        private Double yearLow;
        private Double currentPrice;
        private String lastUpdated;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class NewsItem {
        private String title;
        private String description;
        private String url;
        private String source;
        private String publishedAt;
        private String sentiment; // positive, negative, neutral
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CorrelationData {
        private Double niftyCorrelation;
        private Double sectorIndexCorrelation;
        private String sectorIndexName;
        private Map<String, Double> relatedStocksCorrelation;
        private String correlationPeriod; // e.g., "30 days"
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SocialSentimentData {
        private String overallSentiment; // bullish, bearish, neutral
        private Integer bullishCount;
        private Integer bearishCount;
        private Integer neutralCount;
        private List<PublicSnapshotSummary> recentSnapshots;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PublicSnapshotSummary {
        private Long snapshotId;
        private String username;
        private String patternType;
        private String userComment;
        private String aiValidation;
        private Integer likesCount;
        private Integer commentsCount;
        private String createdAt;
        private String timeframe;
    }
}
