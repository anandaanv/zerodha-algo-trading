package com.dtech.kitecon.screener.elliott.service;

import com.dtech.algo.runner.candle.LiveCandleCache;
import com.dtech.algo.series.Interval;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.screener.elliott.dto.TradeAlertDto;
import com.dtech.kitecon.screener.elliott.entity.ElliottTradeSuggestion;
import com.dtech.kitecon.screener.elliott.entity.SuggestionState;
import com.dtech.kitecon.screener.elliott.repository.ElliottTradeSuggestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ElliottTradeMonitoringService {

    private final ElliottTradeSuggestionRepository suggestionRepository;
    private final LiveCandleCache liveCache;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;

    @Transactional
    public void checkOpenSuggestions() {
        List<ElliottTradeSuggestion> open = suggestionRepository.findByStateIn(
                List.of(SuggestionState.ANTICIPATORY, SuggestionState.ACTIVE));

        if (open.isEmpty()) return;
        log.debug("[TradeMonitor] Checking {} open suggestions", open.size());

        for (ElliottTradeSuggestion s : open) {
            try {
                checkSuggestion(s);
            } catch (Exception e) {
                log.warn("[TradeMonitor] Error checking suggestion {}: {}", s.getId(), e.getMessage());
            }
        }
    }

    private void checkSuggestion(ElliottTradeSuggestion s) {
        if (s.getEntryLow() == null && s.getStopLossPrice() == null && s.getTarget1Price() == null) return;

        OhlcBarDTO bar = liveCache.getLiveCandle(s.getSymbol(), Interval.OneMinute);
        if (bar == null) return;

        double price = bar.getClose();
        boolean isLong = "LONG".equalsIgnoreCase(s.getDirection());

        if (s.getState() == SuggestionState.ANTICIPATORY) {
            // Check if price entered entry zone → auto-activate
            if (s.getEntryLow() != null && s.getEntryHigh() != null) {
                double lo = s.getEntryLow().doubleValue();
                double hi = s.getEntryHigh().doubleValue();
                if (price >= lo && price <= hi) {
                    autoActivate(s, price);
                    return;
                }
            }
            // Check if invalidated before entry (price blew through stop loss)
            if (s.getStopLossPrice() != null) {
                double sl = s.getStopLossPrice().doubleValue();
                if ((isLong && price < sl) || (!isLong && price > sl)) {
                    autoInvalidate(s, price, "Stop loss breached before entry");
                    return;
                }
            }
        } else if (s.getState() == SuggestionState.ACTIVE) {
            // Check target hit
            if (s.getTarget1Price() != null) {
                double t1 = s.getTarget1Price().doubleValue();
                if ((isLong && price >= t1) || (!isLong && price <= t1)) {
                    autoClose(s, true, price, "Target 1 hit");
                    return;
                }
            }
            // Check stop loss breach
            if (s.getStopLossPrice() != null) {
                double sl = s.getStopLossPrice().doubleValue();
                if ((isLong && price < sl) || (!isLong && price > sl)) {
                    autoClose(s, false, price, "Stop loss breached");
                    return;
                }
            }
        }
    }

    private void autoActivate(ElliottTradeSuggestion s, double price) {
        s.setState(SuggestionState.ACTIVE);
        s.setActivatedAt(Instant.now());
        appendNote(s, String.format("Auto-activated at %.2f: price entered entry zone", price));
        suggestionRepository.save(s);
        notify(s, "ENTRY_HIT", price, "Price entered entry zone — trade is now ACTIVE");
        log.info("[TradeMonitor] ENTRY_HIT: {} {} @ {} (suggestion {})", s.getDirection(), s.getSymbol(), String.format("%.2f", price), s.getId());
    }

    private void autoInvalidate(ElliottTradeSuggestion s, double price, String reason) {
        s.setState(SuggestionState.FAILED);
        s.setClosedAt(Instant.now());
        appendNote(s, String.format("Auto-invalidated at %.2f: %s", price, reason));
        suggestionRepository.save(s);
        notify(s, "INVALIDATED", price, reason);
        log.info("[TradeMonitor] INVALIDATED: {} {} @ {} - {} (suggestion {})", s.getDirection(), s.getSymbol(), String.format("%.2f", price), reason, s.getId());
    }

    private void autoClose(ElliottTradeSuggestion s, boolean successful, double price, String reason) {
        s.setState(successful ? SuggestionState.SUCCESSFUL : SuggestionState.FAILED);
        s.setClosedAt(Instant.now());
        appendNote(s, String.format("Auto-closed at %.2f: %s", price, reason));
        suggestionRepository.save(s);
        String event = successful ? "TARGET_HIT" : "STOP_LOSS_HIT";
        notify(s, event, price, reason);
        log.info("[TradeMonitor] {}: {} {} @ {} (suggestion {})", event, s.getDirection(), s.getSymbol(), String.format("%.2f", price), s.getId());
    }

    private void notify(ElliottTradeSuggestion s, String event, double price, String message) {
        TradeAlertDto alert = TradeAlertDto.builder()
                .suggestionId(s.getId())
                .event(event)
                .symbol(s.getSymbol())
                .direction(s.getDirection())
                .price(price)
                .message(message)
                .timestamp(Instant.now())
                .build();

        // Send to user-specific topic using Spring Security principal (username)
        String username = userRepository.findById(s.getUserId())
                .map(User::getUsername)
                .orElse(null);
        if (username != null) {
            messagingTemplate.convertAndSendToUser(username, "/topic/trade-alerts", alert);
        }
    }

    private void appendNote(ElliottTradeSuggestion s, String note) {
        String existing = s.getUserNotes();
        s.setUserNotes(existing != null && !existing.isBlank() ? existing + "\n" + note : note);
    }
}
