package com.dtech.kitecon.service;

import com.dtech.kitecon.repository.ChartSnapshotRepository;
import com.dtech.kitecon.service.model.StockAnalysisResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SnapshotSummaryService - Provides quick analysis summary for snapshots
 * Combines fundamental data, recent sentiment, and key levels
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SnapshotSummaryService {

    private final StockAnalysisService analysisService;
    private final ChartSnapshotRepository snapshotRepository;

    /**
     * Get quick analysis summary for a symbol and timeframe
     * This is shown to users before they create a snapshot to provide context
     */
    public SnapshotSummary getQuickSummary(String symbol, String timeframe) {
        try {
            log.info("Generating snapshot summary for {} {}", symbol, timeframe);

            // Get full analysis
            StockAnalysisResponse analysis = analysisService.analyzeStock(symbol, timeframe);

            // Get recent community sentiment from public snapshots
            int recentSnapshotsCount = (int) snapshotRepository.countBySymbol(symbol);

            // Get most common tags from recent snapshots
            List<String> commonTags = getCommonTags(symbol, 5);

            // Build summary
            SnapshotSummary summary = SnapshotSummary.builder()
                .symbol(symbol)
                .timeframe(timeframe)
                .price(analysis.getFundamentals() != null ?
                    analysis.getFundamentals().getCurrentPrice() : null)
                .peRatio(analysis.getFundamentals() != null ?
                    analysis.getFundamentals().getPeRatio() : null)
                .sector(analysis.getFundamentals() != null ?
                    analysis.getFundamentals().getSector() : null)
                .sentiment(analysis.getSocialSentiment() != null ?
                    analysis.getSocialSentiment().getOverallSentiment() : "neutral")
                .recentSnapshotsCount(recentSnapshotsCount)
                .commonTags(commonTags)
                .latestNews(analysis.getNews() != null && !analysis.getNews().isEmpty() ?
                    analysis.getNews().get(0).getTitle() : null)
                .build();

            return summary;

        } catch (Exception e) {
            log.error("Error generating snapshot summary", e);
            // Return minimal summary on error
            return SnapshotSummary.builder()
                .symbol(symbol)
                .timeframe(timeframe)
                .sentiment("neutral")
                .recentSnapshotsCount(0)
                .commonTags(List.of())
                .build();
        }
    }

    /**
     * Get most common pattern tags from recent snapshots for a symbol
     */
    private List<String> getCommonTags(String symbol, int limit) {
        try {
            var snapshots = snapshotRepository.findBySymbolOrderByCreatedAtDesc(
                symbol,
                PageRequest.of(0, 50)
            );

            // Count tag frequencies
            Map<String, Integer> tagFrequency = new HashMap<>();

            for (var snapshot : snapshots.getContent()) {
                List<String> tags = snapshot.getPatternTagsList();
                for (String tag : tags) {
                    tagFrequency.put(tag, tagFrequency.getOrDefault(tag, 0) + 1);
                }
            }

            // Return top N tags by frequency
            return tagFrequency.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error getting common tags", e);
            return List.of();
        }
    }

    /**
     * Generate compact summary text for display
     */
    public String generateSummaryText(SnapshotSummary summary) {
        StringBuilder text = new StringBuilder();

        // Price and fundamentals
        if (summary.getPrice() != null) {
            text.append(String.format("₹%.2f", summary.getPrice()));
        }

        if (summary.getPeRatio() != null) {
            text.append(String.format(" | PE: %.1f", summary.getPeRatio()));
        }

        if (summary.getSector() != null) {
            text.append(" | ").append(summary.getSector());
        }

        // Sentiment
        if (summary.getSentiment() != null && !summary.getSentiment().equals("neutral")) {
            text.append("\n");
            String sentimentEmoji = getSentimentEmoji(summary.getSentiment());
            text.append(sentimentEmoji).append(" ")
                .append(capitalize(summary.getSentiment()));

            if (summary.getRecentSnapshotsCount() > 0) {
                text.append(String.format(" (%d snapshots)", summary.getRecentSnapshotsCount()));
            }
        }

        // Latest news
        if (summary.getLatestNews() != null) {
            text.append("\n📰 ").append(truncate(summary.getLatestNews(), 60));
        }

        // Common tags
        if (summary.getCommonTags() != null && !summary.getCommonTags().isEmpty()) {
            text.append("\n🏷️ Popular: ").append(String.join(", ", summary.getCommonTags()));
        }

        return text.toString();
    }

    private String getSentimentEmoji(String sentiment) {
        if (sentiment == null) return "";
        switch (sentiment.toLowerCase()) {
            case "bullish":
            case "positive":
                return "📈";
            case "bearish":
            case "negative":
                return "📉";
            default:
                return "➡️";
        }
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength - 3) + "...";
    }

    // ============ DTOs ============

    @Data
    @Builder
    @AllArgsConstructor
    public static class SnapshotSummary {
        private String symbol;
        private String timeframe;
        private Double price;
        private Double peRatio;
        private String sector;
        private String sentiment; // bullish, bearish, neutral
        private Integer recentSnapshotsCount;
        private List<String> commonTags;
        private String latestNews;
    }
}
