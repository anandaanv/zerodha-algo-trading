package com.dtech.algo.strategy.units;

import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.facade.MarketException;
import com.dtech.kitecon.market.facade.MarketFacade;
import com.dtech.kitecon.market.facade.MarketFacadeProvider;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import com.zerodhatech.models.HistoricalData;
import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
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
 * BarSeriesLoader that fetches data from broker APIs via MarketFacade
 * Uses MarketFacadeProvider to support multiple brokers (Zerodha, Dhan, etc.)
 */
@RequiredArgsConstructor
@Service
@Slf4j
@Primary
public class ZerodhaBarSeriesLoader implements BarSeriesLoader {

    private final MarketFacadeProvider marketFacadeProvider;
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

            // Fetch data from broker API via MarketFacade
            HistoricalData historicalData = fetchFromBroker(instrument, barSeriesConfig);

            // Convert to IntervalBarSeries
            IntervalBarSeries intervalBarSeries = buildBarSeries(instrument, historicalData, barSeriesConfig);

            // Cache it
            barSeriesCache.put(key, intervalBarSeries);

            log.info("Loaded {} bars for {} {}",
                intervalBarSeries.getBarCount(),
                instrument.getTradingsymbol(),
                barSeriesConfig.getInterval());

            return intervalBarSeries;

        } catch (MarketException e) {
            log.error("Error loading bar series for {}: {}", barSeriesConfig.getInstrument(), e.getMessage(), e);
            throw new RuntimeException("Failed to load data from broker: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error loading bar series for {}", barSeriesConfig.getInstrument(), e);
            throw new RuntimeException("Failed to load data from broker: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch historical data from broker API
     */
    private HistoricalData fetchFromBroker(Instrument instrument, BarSeriesConfig config) throws MarketException {
        MarketFacade facade = marketFacadeProvider.getFacade();

        Date fromDate = Date.from(config.getStartDate().atZone(ZoneId.systemDefault()).toInstant());
        Date toDate = Date.from(config.getEndDate().atZone(ZoneId.systemDefault()).toInstant());

        String interval = config.getInterval().getKiteKey();
        String token = String.valueOf(instrument.getInstrumentToken());

        log.debug("Fetching from {}: token={}, interval={}, from={}, to={}",
            facade.getBrokerName(), token, interval, fromDate, toDate);

        return facade.getHistoricalData(fromDate, toDate, token, interval, false, false);
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
