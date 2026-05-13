package com.dtech.aitrader.service;

import com.dtech.aitrader.event.PatternSignalEvent;
import com.dtech.aitrader.model.PatternSignal;
import com.dtech.aitrader.repository.AiTraderSymbolRepository;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import com.dtech.ta.patterns.classic.DtbDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LiveDtbDetectionService runs on a schedule (every hour at :05) to detect fresh DTB patterns
 * on enabled symbols and publish PatternSignalEvent for listeners to act on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiveDtbDetectionService {
    private final AiTraderSymbolRepository aiTraderSymbolRepository;
    private final ChartDataService chartDataService;
    private final DtbDetector dtbDetector;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Scheduled scan: every hour at :05 (gives 5 min for hourly bar to close and persist).
     * Cron: minute=5 hour=9-15 day=MON-FRI (9 AM to 3 PM IST, market hours)
     */
    @Scheduled(cron = "0 5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanForDtbPatterns() {
        log.info("LiveDtbDetectionService: scanning for fresh DTB patterns");

        // Load enabled symbols
        var enabledSymbols = aiTraderSymbolRepository.findByPatternAgentEnabledTrue();
        if (enabledSymbols.isEmpty()) {
            log.debug("No symbols with patternAgentEnabled=true");
            return;
        }

        log.info("Scanning {} enabled symbols", enabledSymbols.size());

        for (var symbolRecord : enabledSymbols) {
            try {
                scanSymbol(symbolRecord.getSymbol());
            } catch (Exception e) {
                log.error("Error scanning symbol {}: {}", symbolRecord.getSymbol(), e.getMessage(), e);
            }
        }
    }

    private void scanSymbol(String symbol) {
        // Fetch last 200 hourly bars
        List<OhlcBarDTO> bars;
        try {
            bars = chartDataService.getBars(symbol, "OneHour", null, null, true);
        } catch (Exception e) {
            log.warn("Failed to fetch bars for {}: {}", symbol, e.getMessage());
            return;
        }

        if (bars == null || bars.isEmpty()) {
            log.debug("No bars for {}", symbol);
            return;
        }

        // Build ta4j BarSeries
        BarSeries series = buildBarSeries(bars);

        // Run detector with lookback window (50 bars = ~2 days of hourly)
        var dtbSignalOpt = dtbDetector.detectLatest(series, 50);

        if (dtbSignalOpt.isPresent()) {
            DtbDetector.DtbSignal dtbSignal = dtbSignalOpt.get();

            // Freshness check: signal should be within last 2 bars
            int totalBars = series.getBarCount();
            int signalBar = dtbSignal.retestBarIndex();
            int barsSinceSignal = totalBars - signalBar - 1;

            if (barsSinceSignal > 2) {
                log.debug("DTB signal for {} is not fresh ({}  bars old), skipping", symbol, barsSinceSignal);
                return;
            }

            log.info("Fresh DTB signal detected: symbol={}, direction={}, neckline={}, target={}, retest_bar={}",
                symbol, dtbSignal.direction(), dtbSignal.neckline(), dtbSignal.measuredMoveTarget(), signalBar);

            // Build PatternSignal from DtbSignal
            PatternSignal patternSignal = buildPatternSignal(symbol, dtbSignal);

            // Publish event for listeners
            eventPublisher.publishEvent(new PatternSignalEvent(this, patternSignal));
        }
    }

    private BarSeries buildBarSeries(List<OhlcBarDTO> bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName("temp").build();
        for (OhlcBarDTO bar : bars) {
            Instant timestamp = Instant.ofEpochSecond(bar.getTime());
            Bar taBar = BarsLoader.getBar(
                bar.getOpen(),
                bar.getHigh(),
                bar.getLow(),
                bar.getClose(),
                bar.getVolume(),
                timestamp,
                Duration.ofHours(1)
            );
            series.addBar(taBar);
        }
        return series;
    }

    private PatternSignal buildPatternSignal(String symbol, DtbDetector.DtbSignal dtbSignal) {
        String patternType = "LONG".equals(dtbSignal.direction()) ? "DOUBLE_BOTTOM" : "DOUBLE_TOP";

        PatternSignal signal = new PatternSignal();
        signal.setSymbol(symbol);
        signal.setPatternType(patternType);
        signal.setPatternSource("DTB_LIVE");
        signal.setDirection(dtbSignal.direction());
        signal.setTimeframe("OneHour");
        signal.setSignalRef("live-dtb-" + System.currentTimeMillis());
        signal.setSignalTime(dtbSignal.signalTime());

        // Set entry/SL/target based on pattern
        // For retest entry, use close price (approximated here as neckline for simplicity)
        double entry = dtbSignal.neckline();
        double target = dtbSignal.measuredMoveTarget();
        double atr = dtbSignal.atr();

        signal.setSuggestedEntry(entry);
        signal.setSuggestedTarget(target);

        // 1-OTM SL placement
        if ("LONG".equals(dtbSignal.direction())) {
            // LONG: SL is below entry
            signal.setSuggestedSl(entry - atr);
        } else {
            // SHORT: SL is above entry
            signal.setSuggestedSl(entry + atr);
        }

        // Extra context
        Map<String, Object> context = new HashMap<>();
        context.put("neckline", dtbSignal.neckline());
        context.put("atr", atr);
        context.put("pattern_pivots_count", dtbSignal.patternPivots().size());
        signal.setExtraContext(context);

        return signal;
    }
}
