package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * EOD job — runs at market close (3:30 PM IST, Mon–Fri).
 * Syncs all FNO stock symbols across all relevant timeframes directly via DataFetchService.
 * Independent of subscription config — always covers the full FNO universe.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionUpdaterJob {

    private static final List<Interval> EOD_TIMEFRAMES = List.of(
            Interval.Day,
            Interval.OneHour,
            Interval.FifteenMinute,
            Interval.FiveMinute,
            Interval.ThirtyMinute,
            Interval.FourHours,
            Interval.Week
    );

    private static final List<String> INDEX_FUTURES = List.of(
            "NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "BANKEX", "NIFTYNXT50"
    );

    private final DataFetchService dataFetchService;
    private final InstrumentRepository instrumentRepository;

    // Enable/disable via API if needed
    @Setter
    @Getter
    private volatile boolean enabled = true;

    /**
     * Run at market close (3:30 PM IST) on weekdays.
     * Cron can be overridden via data.update.eodCron property.
     */
    @Scheduled(cron = "${data.update.eodCron:0 30 15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void runUpdateJob() {
        if (!enabled) {
            log.info("[EOD] Job disabled — skipping.");
            return;
        }

        // Refresh instrument master first
        try {
            dataFetchService.downloadAllInstruments();
        } catch (Throwable e) {
            log.warn("[EOD] Instrument download failed: {}", e.getMessage());
        }

        List<String> symbols = instrumentRepository.findDistinctFutureUnderlyingNamesWithNseEquity()
                .stream()
                .filter(s -> s != null && !s.isBlank() && !INDEX_FUTURES.contains(s.toUpperCase()))
                .sorted()
                .toList();

        log.info("[EOD] Starting sync for {} FNO symbols × {} timeframes", symbols.size(), EOD_TIMEFRAMES.size());

        int success = 0, errors = 0;
        for (String symbol : symbols) {
            for (Interval tf : EOD_TIMEFRAMES) {
                try {
                    dataFetchService.updateInstrumentToLatest(symbol, tf, new String[]{"NSE"});
                    success++;
                } catch (Exception e) {
                    errors++;
                    log.warn("[EOD] Failed {}/{}: {}", symbol, tf, e.getMessage());
                }
            }
        }

        log.info("[EOD] Sync complete — success={} errors={}", success, errors);
    }

}
