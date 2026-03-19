package com.dtech.kitecon.service;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.ChartSnapshot;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.StockAnalysisCache;
import com.dtech.kitecon.repository.ChartSnapshotRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.SnapshotCommentRepository;
import com.dtech.kitecon.repository.StockAnalysisCacheRepository;
import com.dtech.kitecon.service.model.StockAnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * StockAnalysisService - Provides comprehensive stock analysis
 * Includes fundamentals, news, correlation, and social sentiment
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockAnalysisService {

    private final StockAnalysisCacheRepository cacheRepository;
    private final ChartSnapshotRepository snapshotRepository;
    private final SnapshotCommentRepository commentRepository;
    private final InstrumentRepository instrumentRepository;
    private final KiteConnectConfig kiteConnectConfig;
    private final ObjectMapper objectMapper;

    // Cache TTL constants (in hours)
    private static final int FUNDAMENTALS_TTL_HOURS = 24;
    private static final int NEWS_TTL_MINUTES = 15;
    private static final int CORRELATION_TTL_HOURS = 1;
    private static final int SOCIAL_TTL_MINUTES = 30;

    /**
     * Get complete stock analysis for a symbol and timeframe
     */
    @Transactional
    public StockAnalysisResponse analyzeStock(String symbol, String timeframe) {
        log.info("Analyzing stock: {} with timeframe: {}", symbol, timeframe);

        return StockAnalysisResponse.builder()
            .symbol(symbol)
            .timeframe(timeframe)
            .fundamentals(getFundamentals(symbol))
            .news(getNews(symbol))
            .correlation(getCorrelation(symbol))
            .socialSentiment(getSocialSentiment(symbol))
            .build();
    }

    /**
     * Get fundamentals data (with caching)
     */
    private StockAnalysisResponse.FundamentalsData getFundamentals(String symbol) {
        String analysisType = "fundamentals";

        // Check cache first
        StockAnalysisCache cached = cacheRepository
            .findValidCache(symbol, analysisType, LocalDateTime.now())
            .orElse(null);

        if (cached != null) {
            try {
                return objectMapper.readValue(
                    cached.getAnalysisData(),
                    StockAnalysisResponse.FundamentalsData.class
                );
            } catch (Exception e) {
                log.error("Error parsing cached fundamentals", e);
            }
        }

        // Fetch fresh data (placeholder - integrate with actual data source)
        StockAnalysisResponse.FundamentalsData fundamentals = fetchFundamentalsFromExternalApi(symbol);

        // Cache the result
        try {
            StockAnalysisCache cache = StockAnalysisCache.builder()
                .symbol(symbol)
                .analysisType(analysisType)
                .analysisData(objectMapper.writeValueAsString(fundamentals))
                .generatedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(FUNDAMENTALS_TTL_HOURS))
                .build();
            cacheRepository.save(cache);
        } catch (Exception e) {
            log.error("Error caching fundamentals", e);
        }

        return fundamentals;
    }

    /**
     * Get recent news (with caching)
     */
    private List<StockAnalysisResponse.NewsItem> getNews(String symbol) {
        String analysisType = "news";

        // Check cache first
        StockAnalysisCache cached = cacheRepository
            .findValidCache(symbol, analysisType, LocalDateTime.now())
            .orElse(null);

        if (cached != null) {
            try {
                return objectMapper.readValue(
                    cached.getAnalysisData(),
                    objectMapper.getTypeFactory().constructCollectionType(
                        List.class,
                        StockAnalysisResponse.NewsItem.class
                    )
                );
            } catch (Exception e) {
                log.error("Error parsing cached news", e);
            }
        }

        // Fetch fresh news (placeholder - integrate with actual news API)
        List<StockAnalysisResponse.NewsItem> news = fetchNewsFromExternalApi(symbol);

        // Cache the result
        try {
            StockAnalysisCache cache = StockAnalysisCache.builder()
                .symbol(symbol)
                .analysisType(analysisType)
                .analysisData(objectMapper.writeValueAsString(news))
                .generatedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(NEWS_TTL_MINUTES))
                .build();
            cacheRepository.save(cache);
        } catch (Exception e) {
            log.error("Error caching news", e);
        }

        return news;
    }

    /**
     * Get correlation data (with caching)
     */
    private StockAnalysisResponse.CorrelationData getCorrelation(String symbol) {
        String analysisType = "correlation";

        // Check cache first
        StockAnalysisCache cached = cacheRepository
            .findValidCache(symbol, analysisType, LocalDateTime.now())
            .orElse(null);

        if (cached != null) {
            try {
                return objectMapper.readValue(
                    cached.getAnalysisData(),
                    StockAnalysisResponse.CorrelationData.class
                );
            } catch (Exception e) {
                log.error("Error parsing cached correlation", e);
            }
        }

        // Calculate correlation (placeholder - implement actual correlation calculation)
        StockAnalysisResponse.CorrelationData correlation = calculateCorrelation(symbol);

        // Cache the result
        try {
            StockAnalysisCache cache = StockAnalysisCache.builder()
                .symbol(symbol)
                .analysisType(analysisType)
                .analysisData(objectMapper.writeValueAsString(correlation))
                .generatedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(CORRELATION_TTL_HOURS))
                .build();
            cacheRepository.save(cache);
        } catch (Exception e) {
            log.error("Error caching correlation", e);
        }

        return correlation;
    }

    /**
     * Get social sentiment from public snapshots
     */
    private StockAnalysisResponse.SocialSentimentData getSocialSentiment(String symbol) {
        // Get recent public snapshots for this symbol
        List<ChartSnapshot> snapshots = snapshotRepository
            .findBySymbolAndVisibilityOrderByCreatedAtDesc(
                symbol,
                ChartSnapshot.SnapshotVisibility.PUBLIC,
                PageRequest.of(0, 20)
            )
            .getContent();

        int bullishCount = 0;
        int bearishCount = 0;
        int neutralCount = 0;

        List<StockAnalysisResponse.PublicSnapshotSummary> snapshotSummaries = new ArrayList<>();

        for (ChartSnapshot snapshot : snapshots) {
            // Analyze sentiment from AI validation and user comment
            String sentiment = analyzeSentiment(snapshot.getAiValidation(), snapshot.getUserComment());

            if ("bullish".equals(sentiment)) bullishCount++;
            else if ("bearish".equals(sentiment)) bearishCount++;
            else neutralCount++;

            // Get comment count for this snapshot
            long commentCount = commentRepository.countBySnapshotId(snapshot.getId());

            snapshotSummaries.add(StockAnalysisResponse.PublicSnapshotSummary.builder()
                .snapshotId(snapshot.getId())
                .username(snapshot.getUsername())
                .patternType(snapshot.getPatternType())
                .userComment(truncateComment(snapshot.getUserComment()))
                .aiValidation(truncateComment(snapshot.getAiValidation()))
                .likesCount(snapshot.getLikesCount())
                .commentsCount((int) commentCount)
                .createdAt(snapshot.getCreatedAt().toString())
                .timeframe(snapshot.getTimeframe())
                .build());
        }

        String overallSentiment = determineOverallSentiment(bullishCount, bearishCount, neutralCount);

        return StockAnalysisResponse.SocialSentimentData.builder()
            .overallSentiment(overallSentiment)
            .bullishCount(bullishCount)
            .bearishCount(bearishCount)
            .neutralCount(neutralCount)
            .recentSnapshots(snapshotSummaries)
            .build();
    }

    // ============ Helper Methods (Placeholders for External API Integration) ============

    private StockAnalysisResponse.FundamentalsData fetchFundamentalsFromExternalApi(String symbol) {
        // Real implementation using Zerodha Kite API
        try {
            // Resolve instrument from symbol
            Instrument instrument = resolveInstrument(symbol);
            if (instrument == null) {
                log.warn("Instrument not found for symbol: {}", symbol);
                return createFallbackFundamentals(symbol);
            }

            // Fetch quote data from Zerodha
            com.zerodhatech.models.Quote quote = getQuoteFromZerodha(instrument);
            if (quote == null) {
                log.warn("Quote data not available for symbol: {}", symbol);
                return createFallbackFundamentals(symbol);
            }

            // Build fundamentals from quote data
            // Note: yearHigh/Low need to be calculated from historical data or use dayHigh/Low as approximation
            double currentPrice = quote.lastPrice > 0 ? quote.lastPrice :
                (quote.ohlc != null ? quote.ohlc.close : 0.0);
            double dayHigh = quote.ohlc != null ? quote.ohlc.high : 0.0;
            double dayLow = quote.ohlc != null ? quote.ohlc.low : 0.0;

            return StockAnalysisResponse.FundamentalsData.builder()
                .currentPrice(currentPrice)
                .yearHigh(dayHigh)  // Using day high as approximation (real implementation needs 52-week data)
                .yearLow(dayLow)    // Using day low as approximation (real implementation needs 52-week data)
                .peRatio(extractPE(symbol))  // PE not available in quote, need external source
                .marketCap(extractMarketCap(symbol))  // Not in quote
                .epsValue(0.0)  // Not in quote
                .dividendYield(0.0)  // Not in quote
                .sector(instrument.getSegment() != null ? instrument.getSegment() : "Unknown")
                .industry(instrument.getExchange() != null ? instrument.getExchange() : "Unknown")
                .lastUpdated(LocalDateTime.now().toString())
                .build();

        } catch (Exception e) {
            log.error("Error fetching fundamentals from Zerodha for symbol: {}", symbol, e);
            return createFallbackFundamentals(symbol);
        }
    }

    /**
     * Resolve instrument from symbol name
     */
    private Instrument resolveInstrument(String symbol) {
        // Try to find by trading symbol (NSE/BSE equity)
        String[] exchanges = {"NSE", "BSE"};
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, exchanges);

        if (instrument != null) {
            return instrument;
        }

        // Fallback: search by prefix
        List<Instrument> prefixMatches =
            instrumentRepository.findAllByTradingsymbolStartingWith(symbol);

        if (prefixMatches != null && !prefixMatches.isEmpty()) {
            // Return first NSE equity if available
            return prefixMatches.stream()
                .filter(i -> "NSE".equals(i.getExchange()))
                .findFirst()
                .orElse(prefixMatches.get(0));
        }

        return null;
    }

    /**
     * Fetch quote from Zerodha Kite API
     */
    private com.zerodhatech.models.Quote getQuoteFromZerodha(Instrument instrument) {
        try {
            // Note: KiteConnect SDK has getQuote() method that returns detailed data
            // Format: "EXCHANGE:SYMBOL" (e.g., "NSE:INFY")
            String instrumentKey = instrument.getExchange() + ":" + instrument.getTradingsymbol();

            Map<String, com.zerodhatech.models.Quote> quotes =
                kiteConnectConfig.getKiteConnect().getQuote(new String[]{instrumentKey});

            return quotes.get(instrumentKey);

        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException e) {
            log.error("KiteException fetching quote for instrument: {}:{} - {}",
                instrument.getExchange(), instrument.getTradingsymbol(), e.getMessage());
            return null;
        } catch (java.io.IOException e) {
            log.error("IOException fetching quote for instrument: {}:{} - {}",
                instrument.getExchange(), instrument.getTradingsymbol(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Unexpected error fetching quote from Zerodha for instrument: {}:{}",
                instrument.getExchange(), instrument.getTradingsymbol(), e);
            return null;
        }
    }

    /**
     * Create fallback fundamentals with dummy data
     */
    private StockAnalysisResponse.FundamentalsData createFallbackFundamentals(String symbol) {
        return StockAnalysisResponse.FundamentalsData.builder()
            .peRatio(0.0)
            .marketCap(0.0)
            .epsValue(0.0)
            .dividendYield(0.0)
            .sector("Unknown")
            .industry("Unknown")
            .yearHigh(0.0)
            .yearLow(0.0)
            .currentPrice(0.0)
            .lastUpdated(LocalDateTime.now().toString())
            .build();
    }

    /**
     * Extract PE ratio (requires external fundamental data API)
     * TODO: Integrate with NSE/BSE API or financial data provider
     */
    private Double extractPE(String symbol) {
        // Placeholder - needs integration with fundamental data provider
        return 0.0;
    }

    /**
     * Extract market cap (requires external fundamental data API)
     * TODO: Integrate with NSE/BSE API or financial data provider
     */
    private Double extractMarketCap(String symbol) {
        // Placeholder - needs integration with fundamental data provider
        return 0.0;
    }

    private List<StockAnalysisResponse.NewsItem> fetchNewsFromExternalApi(String symbol) {
        // Real news integration would require API keys for services like:
        // - NewsAPI (newsapi.org)
        // - Alpha Vantage
        // - MoneyControl API
        // - Economic Times API
        //
        // Implementation template:
        // 1. Make HTTP request to news API with symbol parameter
        // 2. Parse JSON response
        // 3. Transform to StockAnalysisResponse.NewsItem
        // 4. Return list of news items
        //
        // For now, returning empty list until API keys are configured
        // Once API is integrated, cache TTL (NEWS_TTL_MINUTES) will apply

        List<StockAnalysisResponse.NewsItem> news = new ArrayList<>();

        log.info("News API not yet integrated for symbol: {}. Configure news API keys in application.properties", symbol);

        // Placeholder: Return empty list (better than dummy data)
        // When implementing, add configuration properties:
        // news.api.key=YOUR_API_KEY
        // news.api.url=https://newsapi.org/v2/everything

        return news;
    }

    private StockAnalysisResponse.CorrelationData calculateCorrelation(String symbol) {
        // Real correlation calculation using historical price data
        try {
            Instrument instrument = resolveInstrument(symbol);
            if (instrument == null) {
                log.warn("Instrument not found for correlation calculation: {}", symbol);
                return createFallbackCorrelation();
            }

            // Calculate correlation with NIFTY50
            double niftyCorr = calculatePairCorrelation(symbol, "NIFTY 50");

            // Determine sector and calculate sector index correlation
            String sectorIndex = determineSectorIndex(instrument);
            double sectorCorr = calculatePairCorrelation(symbol, sectorIndex);

            // Find top correlated stocks in same sector (simplified - would need sector filtering)
            Map<String, Double> relatedStocks = findCorrelatedStocks(symbol, instrument);

            return StockAnalysisResponse.CorrelationData.builder()
                .niftyCorrelation(niftyCorr)
                .sectorIndexCorrelation(sectorCorr)
                .sectorIndexName(sectorIndex)
                .relatedStocksCorrelation(relatedStocks)
                .correlationPeriod("30 days")
                .build();

        } catch (Exception e) {
            log.error("Error calculating correlation for symbol: {}", symbol, e);
            return createFallbackCorrelation();
        }
    }

    /**
     * Calculate Pearson correlation between two symbols using closing prices
     */
    private double calculatePairCorrelation(String symbol1, String symbol2) {
        try {
            // This would require:
            // 1. Fetch 30 days of closing prices for both symbols from Candle table
            // 2. Calculate Pearson correlation coefficient
            // 3. Return value between -1 and 1
            //
            // Simplified implementation - returns 0.0 for now
            // Real implementation would use:
            // - CandleRepository to fetch historical data
            // - Apache Commons Math for correlation calculation

            log.debug("Correlation calculation between {} and {} requires historical data", symbol1, symbol2);
            return 0.0;

        } catch (Exception e) {
            log.error("Error calculating pair correlation", e);
            return 0.0;
        }
    }

    /**
     * Determine appropriate sector index for the instrument
     */
    private String determineSectorIndex(Instrument instrument) {
        // Map instrument segment/sector to appropriate index
        String segment = instrument.getSegment();
        if (segment == null) {
            return "NIFTY 50";
        }

        // Basic sector mapping (should be enhanced with proper sector data)
        return switch (segment.toUpperCase()) {
            case "IT", "TECHNOLOGY" -> "NIFTY IT";
            case "BANK", "BANKING" -> "NIFTY BANK";
            case "PHARMA", "PHARMACEUTICAL" -> "NIFTY PHARMA";
            case "AUTO", "AUTOMOBILE" -> "NIFTY AUTO";
            case "FMCG" -> "NIFTY FMCG";
            case "METAL", "METALS" -> "NIFTY METAL";
            case "REALTY", "REAL ESTATE" -> "NIFTY REALTY";
            case "ENERGY", "OIL GAS" -> "NIFTY ENERGY";
            default -> "NIFTY 50";
        };
    }

    /**
     * Find stocks correlated with the given symbol
     */
    private Map<String, Double> findCorrelatedStocks(String symbol, Instrument instrument) {
        Map<String, Double> correlations = new HashMap<>();

        // This would require:
        // 1. Get list of stocks in same sector
        // 2. Calculate correlation with each
        // 3. Return top 3-5 correlated stocks
        //
        // Placeholder implementation
        log.debug("Finding correlated stocks for {} requires sector analysis", symbol);

        return correlations;
    }

    /**
     * Create fallback correlation data
     */
    private StockAnalysisResponse.CorrelationData createFallbackCorrelation() {
        return StockAnalysisResponse.CorrelationData.builder()
            .niftyCorrelation(0.0)
            .sectorIndexCorrelation(0.0)
            .sectorIndexName("N/A")
            .relatedStocksCorrelation(new HashMap<>())
            .correlationPeriod("30 days")
            .build();
    }

    private String analyzeSentiment(String aiValidation, String userComment) {
        // Simple sentiment analysis based on keywords
        String combined = (aiValidation + " " + userComment).toLowerCase();

        if (combined.contains("bullish") || combined.contains("buy") ||
            combined.contains("uptrend") || combined.contains("breakout")) {
            return "bullish";
        } else if (combined.contains("bearish") || combined.contains("sell") ||
                   combined.contains("downtrend") || combined.contains("breakdown")) {
            return "bearish";
        }

        return "neutral";
    }

    private String determineOverallSentiment(int bullish, int bearish, int neutral) {
        int total = bullish + bearish + neutral;
        if (total == 0) return "neutral";

        if (bullish > bearish && bullish > neutral) return "bullish";
        if (bearish > bullish && bearish > neutral) return "bearish";
        return "neutral";
    }

    private String truncateComment(String comment) {
        if (comment == null) return null;
        if (comment.length() <= 150) return comment;
        return comment.substring(0, 147) + "...";
    }

    /**
     * Clear expired cache entries (should be called by scheduled job)
     */
    @Transactional
    public void clearExpiredCache() {
        cacheRepository.deleteExpiredCache(LocalDateTime.now());
        log.info("Cleared expired cache entries");
    }

    /**
     * Invalidate cache for a specific symbol
     */
    @Transactional
    public void invalidateCache(String symbol) {
        cacheRepository.deleteBySymbol(symbol);
        log.info("Invalidated cache for symbol: {}", symbol);
    }
}
