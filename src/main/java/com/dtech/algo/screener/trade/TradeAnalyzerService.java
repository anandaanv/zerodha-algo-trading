package com.dtech.algo.screener.trade;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.InstrumentLtp;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentLtpRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeAnalyzerService {

    private final IdentifiedTradeRepository tradeRepository;
    private final InstrumentLtpRepository ltpRepository;
    private final InstrumentRepository instrumentRepository;
    private final CandleRepository candleRepository;

    @Scheduled(fixedRate = 15 * 60 * 1000) // Run every 15 minutes
    @Transactional
    public void analyzeOpenTrades() {
        log.info("Starting trade analysis...");
        try {
            List<IdentifiedTrade> openTrades = tradeRepository.findAllByOpenIsTrue();
            log.info("Found {} open trades to analyze", openTrades.size());

            for (IdentifiedTrade trade : openTrades) {
                try {
                    analyzeTrade(trade);
                } catch (Exception e) {
                    log.error("Error analyzing trade {}: {}", trade.getId(), e.getMessage(), e);
                }
            }
            log.info("Trade analysis completed");
        } catch (Exception e) {
            log.error("Error in trade analysis job: {}", e.getMessage(), e);
        }
    }

    private void analyzeTrade(IdentifiedTrade trade) {
        // Get current price
        Optional<InstrumentLtp> ltpOpt = ltpRepository.findByTradingSymbol(trade.getScript());
        if (ltpOpt.isEmpty()) {
            log.warn("No LTP found for trade {} script {}", trade.getId(), trade.getScript());
            return;
        }

        Double currentPrice = ltpOpt.get().getLtp();
        if (currentPrice == null) {
            log.warn("LTP is null for trade {} script {}", trade.getId(), trade.getScript());
            return;
        }

        // Check price-based exit conditions
        if (checkPriceBasedExit(trade, currentPrice)) {
            return; // Trade was closed
        }

        // Check time-based exit condition
        checkTimeBasedExit(trade, currentPrice);
    }

    private boolean checkPriceBasedExit(IdentifiedTrade trade, Double currentPrice) {
        String side = trade.getSide();
        boolean isBuy = "BUY".equalsIgnoreCase(side);
        
        // Parse target and stoploss using conservative values
        Double target = parseTargetPrice(trade.getTarget(), isBuy);
        Double stoploss = parseStoplossPrice(trade.getStoploss(), isBuy);

        if (target == null && stoploss == null) {
            return false; // No price levels to check
        }

        // Check target hit
        if (target != null) {
            boolean targetHit = isBuy ? currentPrice >= target : currentPrice <= target;
            if (targetHit) {
                closeTrade(trade, TradeStatus.TARGET_HIT, 
                    String.format("Target hit: current=%.2f, target=%.2f", currentPrice, target));
                return true;
            }
        }

        // Check stoploss hit
        if (stoploss != null) {
            boolean stoplossHit = isBuy ? currentPrice <= stoploss : currentPrice >= stoploss;
            if (stoplossHit) {
                closeTrade(trade, TradeStatus.STOPLOSS_HIT, 
                    String.format("Stoploss hit: current=%.2f, stoploss=%.2f", currentPrice, stoploss));
                return true;
            }
        }

        return false;
    }

    private void checkTimeBasedExit(IdentifiedTrade trade, Double currentPrice) {
        // Get instrument
        List<Instrument> instruments = instrumentRepository.findAllByTradingsymbol(trade.getScript());
        if (instruments.isEmpty()) {
            log.warn("No instrument found for script {}", trade.getScript());
            return;
        }
        Instrument instrument = instruments.get(0);

        // Get last 10 candles since trade was triggered
        Instant tradeTime = trade.getTimeTriggered();
        Instant now = Instant.now();
        
        List<Candle> candles = candleRepository.findAllByInstrumentAndTimeframeAndTimestampBetween(
            instrument, trade.getTimeframe(), tradeTime, now
        );

        if (candles.size() < 10) {
            log.debug("Not enough candles ({}) for time-based analysis of trade {}", candles.size(), trade.getId());
            return;
        }

        // Get last 10 candles
        List<Candle> last10Candles = candles.subList(Math.max(0, candles.size() - 10), candles.size());
        
        // Check if there's favorable movement
        boolean hasFavorableMovement = checkFavorableMovement(trade, last10Candles, currentPrice);
        
        if (!hasFavorableMovement) {
            closeTrade(trade, TradeStatus.TIME_STOPLOSS, 
                String.format("No favorable movement in last 10 candles. Current price: %.2f", currentPrice));
        }
    }

    private boolean checkFavorableMovement(IdentifiedTrade trade, List<Candle> candles, Double currentPrice) {
        String side = trade.getSide();
        boolean isBuy = "BUY".equalsIgnoreCase(side);
        
        Double entryPrice = parseEntryPrice(trade.getEntry(), isBuy);
        if (entryPrice == null) {
            // Use first candle close as entry
            entryPrice = candles.isEmpty() ? currentPrice : candles.get(0).getClose();
        }

        // Check if current price shows favorable movement (at least 0.5% in the right direction)
        double movementPercent = ((currentPrice - entryPrice) / entryPrice) * 100;
        double threshold = 0.5;

        if (isBuy) {
            return movementPercent > threshold; // Price should be higher for BUY
        } else {
            return movementPercent < -threshold; // Price should be lower for SELL
        }
    }

    private void closeTrade(IdentifiedTrade trade, TradeStatus status, String reason) {
        trade.setOpen(false);
        trade.setStatus(status.name());
        trade.setUpdatedAt(Instant.now());
        
        String logLine = String.format("%s | %s", Instant.now(), reason);
        trade.setLogs((trade.getLogs() == null ? "" : trade.getLogs() + "\n") + logLine);
        
        tradeRepository.save(trade);
        log.info("Trade {} closed with status {}: {}", trade.getId(), status, reason);
    }

    /**
     * Parse price string which can be a single value or a range (e.g., "100-105")
     * @param priceStr Price string to parse
     * @param takeHigher If true, returns the higher value from range; if false, returns lower value
     * @return Parsed price value
     */
    private Double parsePrice(String priceStr, boolean takeHigher) {
        if (priceStr == null || priceStr.trim().isEmpty()) {
            return null;
        }
        try {
            // Check if it's a range (contains hyphen between numbers)
            if (priceStr.matches(".*\\d+\\s*-\\s*\\d+.*")) {
                // Extract numbers from the range
                String[] parts = priceStr.split("-");
                if (parts.length == 2) {
                    String cleaned1 = parts[0].trim().replaceAll("[^0-9.]", "");
                    String cleaned2 = parts[1].trim().replaceAll("[^0-9.]", "");
                    
                    double price1 = Double.parseDouble(cleaned1);
                    double price2 = Double.parseDouble(cleaned2);
                    
                    // Return based on preference
                    return takeHigher ? Math.max(price1, price2) : Math.min(price1, price2);
                }
            }
            
            // Single value - remove any non-numeric characters except dot
            String cleaned = priceStr.replaceAll("[^0-9.]", "");
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse price: {}", priceStr);
            return null;
        }
    }
    
    /**
     * Parse price for entry - uses worst case scenario
     * For BUY: takes higher price (more expensive entry)
     * For SELL: takes lower price (worse entry)
     */
    private Double parseEntryPrice(String priceStr, boolean isBuy) {
        return parsePrice(priceStr, isBuy); // BUY=true (take higher), SELL=false (take lower)
    }
    
    /**
     * Parse price for target - uses conservative target
     * For BUY: takes lower target (closer, more conservative)
     * For SELL: takes higher target (closer, more conservative)
     */
    private Double parseTargetPrice(String priceStr, boolean isBuy) {
        return parsePrice(priceStr, !isBuy); // BUY=false (take lower), SELL=true (take higher)
    }
    
    /**
     * Parse price for stoploss - uses conservative stoploss
     * For BUY: takes higher stoploss (closer, hits earlier)
     * For SELL: takes lower stoploss (closer, hits earlier)
     */
    private Double parseStoplossPrice(String priceStr, boolean isBuy) {
        return parsePrice(priceStr, isBuy); // BUY=true (take higher), SELL=false (take lower)
    }
}
