package com.dtech.algo.strategy.units;

import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * BarSeriesLoader that fetches data directly from Zerodha Kite API
 * instead of loading from database
 */
@RequiredArgsConstructor
@Service
@Slf4j
@Primary
public class ZerodhaBarSeriesLoader implements BarSeriesLoader {

    private final KiteConnectConfig kiteConnectConfig;
    private final InstrumentRepository instrumentRepository;
    private final BarSeriesCache barSeriesCache;

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
            // Resolve instrument
            Instrument instrument = resolveInstrument(barSeriesConfig);

            if (instrument == null) {
                throw new IllegalArgumentException("Instrument not found: " + barSeriesConfig.getInstrument());
            }

            // Fetch data from Zerodha Kite API
            HistoricalData historicalData = fetchFromZerodha(instrument, barSeriesConfig);

            // Convert to IntervalBarSeries
            IntervalBarSeries intervalBarSeries = buildBarSeries(instrument, historicalData, barSeriesConfig);

            // Cache it
            barSeriesCache.put(key, intervalBarSeries);

            log.info("Loaded {} bars from Zerodha for {} {}",
                intervalBarSeries.getBarCount(),
                instrument.getTradingsymbol(),
                barSeriesConfig.getInterval());

            return intervalBarSeries;

        } catch (KiteException | IOException e) {
            log.error("Error loading bar series from Zerodha for {}", barSeriesConfig.getInstrument(), e);
            throw new RuntimeException("Failed to load data from Zerodha: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error loading bar series from Zerodha for {}", barSeriesConfig.getInstrument(), e);
            throw new RuntimeException("Failed to load data from Zerodha: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch historical data from Zerodha Kite API
     */
    private HistoricalData fetchFromZerodha(Instrument instrument, BarSeriesConfig config) throws KiteException, IOException {
        KiteConnect kiteConnect = kiteConnectConfig.getKiteConnect();

        Date fromDate = Date.from(config.getStartDate().atZone(ZoneId.systemDefault()).toInstant());
        Date toDate = Date.from(config.getEndDate().atZone(ZoneId.systemDefault()).toInstant());

        String interval = config.getInterval().getKiteKey();
        String token = String.valueOf(instrument.getInstrumentToken());

        log.debug("Fetching from Zerodha: token={}, interval={}, from={}, to={}",
            token, interval, fromDate, toDate);

        return kiteConnect.getHistoricalData(fromDate, toDate, token, interval, false, false);
    }

    /**
     * Build IntervalBarSeries from Zerodha HistoricalData
     */
    private IntervalBarSeries buildBarSeries(
        Instrument instrument,
        HistoricalData historicalData,
        BarSeriesConfig config
    ) {
        // Create base bar series
        BarSeries series = new BaseBarSeriesBuilder()
            .withName(instrument.getTradingsymbol())
            .build();

        // Add bars from historical data
        if (historicalData != null && historicalData.dataArrayList != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
            historicalData.dataArrayList.forEach(dataPoint -> {
                try {
                    // Parse timestamp string to Instant
                    Instant instant = sdf.parse(dataPoint.timeStamp).toInstant();

                    series.addBar(BarsLoader.getBar(
                        dataPoint.open,
                        dataPoint.high,
                        dataPoint.low,
                        dataPoint.close,
                        dataPoint.volume > 0 ? dataPoint.volume : 0,
                        instant
                    ));
                } catch (Exception e) {
                    log.warn("Failed to parse timestamp: {}", dataPoint.timeStamp, e);
                }
            });
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
     * Resolve instrument from repository
     * Same logic as RdbmsBarSeriesLoader
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
