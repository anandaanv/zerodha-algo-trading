package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.config.HistoricalDateLimit;
import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.google.common.util.concurrent.RateLimiter;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Profile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Log4j2
public class DataFetchService {

    private final InstrumentRepository instrumentRepository;
    private final KiteConnectConfig kiteConnectConfig;
    private final DataDownloader dataDownloader;
    private final HistoricalDateLimit historicalDateLimit;

    private int threadPoolSize = 3;

    private ExecutorService executorService;

    @PostConstruct
    public void setupExecutor() {
        this.executorService = Executors.newFixedThreadPool(threadPoolSize);
    }


    private final CandleRepository candleRepository;

    public String getProfile() throws IOException, KiteException {
        Profile profile = kiteConnectConfig.getKiteConnect().getProfile();
        return profile.userName;
    }

    public void downloadAllInstruments() throws KiteException, IOException {
        Map<Long, Instrument> databaseInstruments = instrumentRepository.findAll()
                .stream()
                .collect(Collectors.toMap(Instrument::getInstrumentToken, instrument -> instrument));
        List<com.zerodhatech.models.Instrument> instruments = kiteConnectConfig.getKiteConnect()
                .getInstruments();
        List<Instrument> newInstruments = instruments.stream()
                .filter(instrument -> !databaseInstruments.containsKey(instrument.instrument_token))
                .map(instrument -> Instrument.builder()
                        .instrumentToken(instrument.instrument_token)
                        .instrumentType(instrument.instrument_type)
                        .strike(instrument.strike)
                        .exchangeToken(instrument.exchange_token)
                        .tradingsymbol(instrument.tradingsymbol)
                        .name(instrument.name)
                        .lastPrice(instrument.last_price)
                        .expiry(instrument.getExpiry() == null ? null : dateToLocalDate(instrument))
                        .tickSize(instrument.tick_size)
                        .lotSize(instrument.lot_size)
                        .instrumentType(instrument.instrument_type)
                        .segment(instrument.segment)
                        .exchange(instrument.exchange)
                        .build()).collect(Collectors.toList());
        instrumentRepository.saveAll(newInstruments);
    }

    private LocalDateTime dateToLocalDate(com.zerodhatech.models.Instrument instrument) {
        return LocalDateTime.ofInstant(instrument.expiry.toInstant(),
                ZoneId.systemDefault());
    }

    public void downloadCandleData(String instrumentName, Interval interval, String[] exchanges) {
        LocalDateTime startDate = LocalDateTime.now().minus(4, ChronoUnit.MONTHS);
        List<Instrument> primaryInstruments = getInstrumentList(instrumentName, exchanges, startDate);
        primaryInstruments.forEach(instrument -> this.downloadData(instrument, interval));
    }

    public void updateInstrumentToLatest(String instrumentName, Interval interval, String[] exchanges) {
        LocalDateTime startDate = LocalDateTime.now().minus(4, ChronoUnit.MONTHS);
        List<Instrument> primaryInstruments = getInstrumentList(instrumentName, exchanges, startDate);
        primaryInstruments.forEach(instrument -> this.updateInstrument(instrument, interval));
    }

    private List<Instrument> getInstrumentList(String instrumentName, String[] exchanges,
                                               LocalDateTime startDate) {
        List<Instrument> instruments = instrumentRepository
                .findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
                        instrumentName, LocalDateTime.now().plusDays(60), startDate, exchanges);
        List<Instrument> primaryInstruments = instrumentRepository
                .findAllByTradingsymbolStartingWithAndExpiryIsNullAndExchangeIn(
                        instrumentName, exchanges);
        instruments.addAll(primaryInstruments);
        instruments.addAll(primaryInstruments);
        return primaryInstruments;
    }

    public void downloadData(Instrument instrument, Interval interval) {
        ZonedDateTime endTime = ZonedDateTime.now();
        int totalAvailableDuration = historicalDateLimit
                .getTotalAvailableDuration(instrument.getExchange(), interval);
        ZonedDateTime startTime = endTime.minusDays(totalAvailableDuration);
        int sliceSize = historicalDateLimit.getDuration(instrument.getExchange(), interval);
        fetchDataAndUpdateDatabase(instrument, interval, endTime, sliceSize, startTime);
    }

    public void updateInstrument(Instrument instrument, Interval interval) {
        ZonedDateTime endTime = ZonedDateTime.now();
        Candle latestCandle = candleRepository
                .findFirstByInstrumentAndTimeframeOrderByTimestampDesc(instrument, interval);
        if (latestCandle == null) {
            downloadData(instrument, interval);
        } else {
            LocalDateTime latestTimestamp = latestCandle.getTimestamp();
            int sliceSize = historicalDateLimit.getDuration(instrument.getExchange(), interval);
            ZonedDateTime startDate = ZonedDateTime.of(latestTimestamp, ZoneId.systemDefault());
            fetchDataAndUpdateDatabase(instrument, interval, endTime, sliceSize, startDate);
        }
    }

    private void fetchDataAndUpdateDatabase(Instrument instrument, Interval interval,
                                            ZonedDateTime endTime, int sliceSize, ZonedDateTime startDate) {
        List<DateRange> dateRangeList = DateRange.builder()
                .endDate(endTime)
                .startDate(startDate)
                .build()
                .split(sliceSize);
        dateRangeList.sort(Comparator.comparing(DateRange::getStartDate));
        dateRangeList.forEach(dateRange -> {
            DataDownloadRequest dataDownloadRequest = DataDownloadRequest.builder()
                    .dateRange(dateRange)
                    .instrument(instrument)
                    .interval(interval)
                    .build();
            RateLimiter rateLimiter = RateLimiter.create(3.0); // rate is "2 permits per second"
            try {
                executorService.submit(
                        () -> {
                            try {
                                dataDownloader.processDownload(dataDownloadRequest);
                                rateLimiter.acquire();

                            } catch (KiteException e) {
                                throw new RuntimeException(e);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                );
            } catch (Throwable e) {
                log.catching(e);
            }
        });
    }
}
