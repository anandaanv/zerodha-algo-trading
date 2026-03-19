package com.dtech.dhan.service;

import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.dhan.client.DhanApiClient;
import com.dtech.dhan.config.DhanConnectConfig;
import com.dtech.dhan.model.DhanHistoricalData;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * BarSeriesLoader that fetches data from Dhan API
 * Converts Dhan historical data to IntervalBarSeries for strategy backtesting
 */
@RequiredArgsConstructor
@Service
@Slf4j
@ConditionalOnProperty(name = "dhan.enabled", havingValue = "true", matchIfMissing = false)
public class DhanBarSeriesLoader implements BarSeriesLoader {

    private final DhanConnectConfig dhanConnectConfig;
    private final DhanApiClient dhanApiClient;
    private final InstrumentRepository instrumentRepository;
    private final BarSeriesCache barSeriesCache;

    // Dhan timestamp format: "2024-01-01T09:15:00+05:30" or similar
    private static final DateTimeFormatter DHAN_TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[XXX]");

    @Override
    public IntervalBarSeries loadBarSeries(BarSeriesConfig barSeriesConfig) {
        String key = barSeriesConfig.getName();

        // Check cache first
        IntervalBarSeries cachedSeries = barSeriesCache.get(key);
        if (cachedSeries != null) {
            log.debug("Returning cached bar series for {}", key);
            return cachedSeries;
        }

        try {
            // Check if Dhan is configured
            if (!dhanConnectConfig.isConfigured()) {
                throw new IllegalStateException("Dhan API not configured. Please set access token.");
            }

            // Resolve instrument
            Instrument instrument = resolveInstrument(barSeriesConfig);
            if (instrument == null) {
                throw new IllegalArgumentException("Instrument not found: " + barSeriesConfig.getInstrument());
            }

            // Fetch data from Dhan API
            List<DhanHistoricalData> historicalData = fetchFromDhan(instrument, barSeriesConfig);

            // Convert to IntervalBarSeries
            IntervalBarSeries intervalBarSeries = buildBarSeries(instrument, historicalData, barSeriesConfig);

            // Cache it
            barSeriesCache.put(key, intervalBarSeries);

            log.info("Loaded {} bars from Dhan for {} {}",
                intervalBarSeries.getBarCount(),
                instrument.getTradingsymbol(),
                barSeriesConfig.getInterval());

            return intervalBarSeries;

        } catch (Exception e) {
            log.error("Error loading bar series from Dhan for {}", barSeriesConfig.getInstrument(), e);
            throw new RuntimeException("Failed to load data from Dhan: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch historical data from Dhan API
     */
    private List<DhanHistoricalData> fetchFromDhan(Instrument instrument, BarSeriesConfig config) {
        // TODO: Map Zerodha instrument token to Dhan securityId
        // For now, use instrument token as securityId (needs proper mapping)
        String securityId = String.valueOf(instrument.getInstrumentToken());
        String exchangeSegment = mapExchangeToDhan(config.getExchange().name());
        String instrumentType = mapInstrumentTypeToDhan(config.getInstrumentType().name());

        String interval = config.getInterval().getKiteKey();

        log.debug("Fetching from Dhan: securityId={}, exchange={}, instrument={}, interval={}, from={}, to={}",
            securityId, exchangeSegment, instrumentType, interval, config.getStartDate(), config.getEndDate());

        // Determine if daily or intraday
        if (interval.equals("day") || interval.equals("1d")) {
            return dhanApiClient.getHistoricalDaily(
                securityId,
                exchangeSegment,
                instrumentType,
                config.getStartDate(),
                config.getEndDate(),
                dhanConnectConfig.getAccessToken()
            );
        } else {
            // Parse interval to minutes
            int intervalMinutes = parseIntervalToMinutes(interval);
            return dhanApiClient.getHistoricalIntraday(
                securityId,
                exchangeSegment,
                instrumentType,
                intervalMinutes,
                config.getStartDate(),
                config.getEndDate(),
                dhanConnectConfig.getAccessToken()
            );
        }
    }

    /**
     * Build IntervalBarSeries from Dhan historical data
     */
    private IntervalBarSeries buildBarSeries(
        Instrument instrument,
        List<DhanHistoricalData> historicalData,
        BarSeriesConfig config
    ) {
        // Create base bar series
        BarSeries series = new BaseBarSeriesBuilder()
            .withName(instrument.getTradingsymbol())
            .build();

        // Add bars from historical data
        if (historicalData != null && !historicalData.isEmpty()) {
            for (DhanHistoricalData dataPoint : historicalData) {
                try {
                    // Parse Dhan timestamp to Instant
                    Instant instant = parseTimestamp(dataPoint.getTimestamp());

                    series.addBar(BarsLoader.getBar(
                        dataPoint.getOpen(),
                        dataPoint.getHigh(),
                        dataPoint.getLow(),
                        dataPoint.getClose(),
                        dataPoint.getVolume() > 0 ? dataPoint.getVolume() : 0,
                        instant
                    ));
                } catch (Exception e) {
                    log.warn("Failed to parse Dhan data point: timestamp={}, error={}",
                        dataPoint.getTimestamp(), e.getMessage());
                }
            }
        }

        // Wrap in ExtendedBarSeries
        return ExtendedBarSeries.builder()
            .interval(config.getInterval())
            .seriesType(config.getSeriesType())
            .delegate(series)
            .instrument(config.getInstrument())
            .build();
    }

    /**
     * Parse Dhan timestamp string to Instant
     * Dhan format: "2024-01-01T09:15:00+05:30" or "2024-01-01T09:15:00"
     */
    private Instant parseTimestamp(String timestamp) {
        try {
            LocalDateTime ldt = LocalDateTime.parse(timestamp, DHAN_TIMESTAMP_FORMATTER);
            return ldt.atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        } catch (Exception e) {
            // Fallback: try simpler format without timezone
            try {
                LocalDateTime ldt = LocalDateTime.parse(timestamp.substring(0, 19),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
                return ldt.atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            } catch (Exception ex) {
                log.warn("Failed to parse timestamp: {}", timestamp);
                throw new RuntimeException("Invalid timestamp format: " + timestamp, ex);
            }
        }
    }

    /**
     * Map Zerodha exchange to Dhan exchange segment
     */
    private String mapExchangeToDhan(String zerodhaExchange) {
        switch (zerodhaExchange) {
            case "NSE":
                return "NSE_EQ";
            case "BSE":
                return "BSE_EQ";
            case "NFO":
                return "NSE_FNO";
            case "BFO":
                return "BSE_FNO";
            case "MCX":
                return "MCX_COMM";
            default:
                log.warn("Unknown exchange: {}, defaulting to NSE_EQ", zerodhaExchange);
                return "NSE_EQ";
        }
    }

    /**
     * Map Zerodha instrument type to Dhan instrument type
     */
    private String mapInstrumentTypeToDhan(String zerodhaInstrumentType) {
        switch (zerodhaInstrumentType) {
            case "EQ":
                return "EQUITY";
            case "FUT":
                return "FUTIDX";
            case "CE":
            case "PE":
                return "OPTIDX";
            default:
                log.warn("Unknown instrument type: {}, defaulting to EQUITY", zerodhaInstrumentType);
                return "EQUITY";
        }
    }

    /**
     * Parse interval string to minutes for Dhan API
     */
    private int parseIntervalToMinutes(String interval) {
        if (interval.contains("minute")) {
            return Integer.parseInt(interval.replace("minute", ""));
        }

        // Map common intervals
        switch (interval) {
            case "1":
            case "1m":
                return 1;
            case "5":
            case "5m":
                return 5;
            case "15":
            case "15m":
                return 15;
            case "25":
            case "25m":
                return 25;
            case "60":
            case "1h":
                return 60;
            default:
                throw new IllegalArgumentException("Unsupported interval for Dhan: " + interval);
        }
    }

    /**
     * Resolve instrument from repository
     * Same logic as ZerodhaBarSeriesLoader
     */
    private Instrument resolveInstrument(BarSeriesConfig barSeriesConfig) {
        List<Instrument> instruments = instrumentRepository
            .findAllByExchangeAndInstrumentTypeAndTradingsymbolStartingWith(
                barSeriesConfig.getExchange().name(),
                barSeriesConfig.getInstrumentType().name(),
                barSeriesConfig.getInstrument()
            );

        if (instruments.isEmpty()) {
            instruments = instrumentRepository
                .findAllByTradingsymbol(barSeriesConfig.getInstrument());
        }

        return instruments.isEmpty() ? null : instruments.getFirst();
    }
}
