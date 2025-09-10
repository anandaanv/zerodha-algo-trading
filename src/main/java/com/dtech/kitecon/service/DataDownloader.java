package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
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
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jfree.data.time.Day;
import org.jfree.data.time.Second;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Transactional
    public void processDownload(DataDownloadRequest downloadRequest)
            throws KiteException, IOException {
        log.info("Download data for " + downloadRequest);
        Interval interval = downloadRequest.getInterval();
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
        DateRange dateRange = downloadRequest.getDateRange();
        Instrument instrument = downloadRequest.getInstrument();
        Instant startDate = dateRange.getStartDate();
        Instant endDate = dateRange.getEndDate();
//    candleRepository
//        .deleteByInstrumentAndTimeframeAndTimestampBetween(instrument, interval,
//            startDate.minusSeconds(1).toLocalDateTime(),
//            endDate.toLocalDateTime().plusSeconds(1));
        try {
            HistoricalData candles = kiteConnectConfig.getKiteConnect().getHistoricalData(Date.from(
                            startDate),
                    Date.from(endDate),
                    String.valueOf(instrument.getInstrumentToken()),
                    interval.getKiteKey(), false, true);
            LocalDateTime timeStart = ZonedDateTime.parse(candles.dataArrayList.getFirst().timeStamp, dateFormat).toLocalDateTime().minusSeconds(1);
            LocalDateTime timesEnd = ZonedDateTime.parse(candles.dataArrayList.getLast().timeStamp, dateFormat).toLocalDateTime().plusSeconds(1);
            List<Candle> existingData = candleRepository.findAllByInstrumentAndTimeframeAndTimestampBetween(instrument, interval,
                    timeStart,
                    timesEnd);

            Map<Instant, Candle> datamap = existingData.stream().collect(Collectors.toMap(candle -> candle.getTimestamp(), candle -> candle));
            List<Candle> databaseCandles = candleFacade.buildCandlesFromOLSHStreamFailSafe(
                    interval, dateFormat, instrument, candles, datamap);
            candleRepository.saveAll(databaseCandles);
        } catch (InputException ex) {
            log.error(ex.message);
        }

    }

}
