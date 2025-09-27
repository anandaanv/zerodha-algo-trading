package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.google.common.util.concurrent.RateLimiter;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.InputException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalUnit;
import java.util.*;
import java.util.stream.Collectors;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jfree.data.time.Day;
import org.jfree.data.time.Second;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Responsibility of this class is to download the data and insert in the database.
 */
@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Log4j2
public class DataDownloader {

    private final KiteConnectConfig kiteConnectConfig;
    private final CandleRepository candleRepository;
    private final CandleFacade candleFacade;
    private static final RateLimiter ratelimit = RateLimiter.create(3.0);

    @Transactional
    public void processDownload(DataDownloadRequest downloadRequest)
            throws KiteException, IOException {
        ratelimit.acquire();
        log.debug("Download data for {}", downloadRequest);
        Interval interval = downloadRequest.getInterval();
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
        DateRange dateRange = downloadRequest.getDateRange();
        Instrument instrument = downloadRequest.getInstrument();
        Instant startDate = dateRange.getStartDate();
        Instant endDate = dateRange.getEndDate();
        long diff = endDate.getEpochSecond() - startDate.getEpochSecond();
        if(diff < 60) {
            log.info("TimeDifference too low, skipping {}", downloadRequest);
            return;
        }
        try {
            HistoricalData candles = kiteConnectConfig.getKiteConnect().getHistoricalData(Date.from(
                            startDate),
                    Date.from(endDate),
                    String.valueOf(instrument.getInstrumentToken()),
                    interval.getKiteKey(), downloadRequest.isContinuous(), true);
            Map<Instant, Candle> dataMap = new HashMap<>();
            if(candles.dataArrayList.isEmpty()) {
                log.error("No data found - {}", downloadRequest);
                return;
            }
            if(downloadRequest.isClean()) {
                candleRepository.deleteByInstrumentAndTimeframe(instrument, interval);
            } else {
                Instant timeStart = CandleFacade.getInstant(dateFormat, candles.dataArrayList.getFirst()).minus(1, ChronoUnit.SECONDS);
                Instant timesEnd = CandleFacade.getInstant(dateFormat, candles.dataArrayList.getLast()).plus(1, ChronoUnit.SECONDS);
                List<Candle> existingData = candleRepository.findAllByInstrumentAndTimeframeAndTimestampBetween(instrument, interval,
                        timeStart,
                        timesEnd);
                dataMap = existingData.stream().collect(Collectors.toMap(candle -> candle.getTimestamp(), candle -> candle));
            }

            List<Candle> databaseCandles = candleFacade.buildCandlesFromOLSHStreamFailSafe(
                    interval, dateFormat, instrument, candles, dataMap);
            candleRepository.saveAll(databaseCandles);
//            // Ensure inserts are flushed so the native update sees latest rows
//            candleRepository.flush();
//            // Fire-and-forget: set LTP for subscription and UOW entries for this trading symbol and timeframe
//            subscriptionRepository.updateLtpFromLatestCandle(instrument.getTradingsymbol(), interval.name());
//            subscriptionUowRepository.updateUowLtpFromLatestCandle(instrument.getTradingsymbol(), interval.name());
        } catch (InputException ex) {
            log.error(ex.getMessage());
        } catch (RuntimeException ex) {
            log.catching(ex);
        }

    }

}
