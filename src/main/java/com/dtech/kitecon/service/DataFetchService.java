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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
        primaryInstruments.forEach(instrument -> this.updateInstrument(instrument, interval, false));
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
        Instant endTime = Instant.now();
        int totalAvailableDuration = historicalDateLimit
                .getTotalAvailableDuration(instrument.getExchange(), interval);
        Instant startTime = endTime.minus(totalAvailableDuration, ChronoUnit.DAYS);
        int sliceSize = historicalDateLimit.getDuration(instrument.getExchange(), interval);
        fetchDataAndUpdateDatabase(instrument, interval, endTime, sliceSize, startTime, false);
    }

    public void updateInstrument(Instrument instrument, Interval interval, boolean clean) {
        Instant endTime = Instant.now();
        Candle latestCandle = candleRepository
                .findFirstByInstrumentAndTimeframeOrderByTimestampDesc(instrument, interval);
        if (latestCandle == null) {
            downloadData(instrument, interval);
        } else {
            Instant latestTimestamp = latestCandle.getTimestamp();
            int sliceSize = historicalDateLimit.getDuration(instrument.getExchange(), interval);
            fetchDataAndUpdateDatabase(instrument, interval, endTime, sliceSize, latestTimestamp, clean);
        }
    }

    public void fetchDataAndUpdateDatabase(Instrument instrument, Interval interval,
                                           Instant endTime, int sliceSize, Instant startDate, boolean clean) {
        List<DateRange> dateRangeList = DateRange.builder()
                .endDate(endTime)
                .startDate(startDate)
                .build()
                .split(sliceSize);
        dateRangeList.sort(Comparator.comparing(DateRange::getStartDate));
        AtomicInteger count = new AtomicInteger(0);
        dateRangeList.forEach(dateRange -> {
            DataDownloadRequest dataDownloadRequest = DataDownloadRequest.builder()
                    .dateRange(dateRange)
                    .instrument(instrument)
                    .interval(interval)
                    .clean(clean && count.getAndAdd(1) <= 0)
                    .build();
            if(dateRange.getEndDate().isBefore(dateRange.getStartDate())) {
                throw new RuntimeException(String.format("Invalid date range {} ", dataDownloadRequest));
            }
            try {
                executorService.submit(
                        () -> {
                            try {
                                dataDownloader.processDownload(dataDownloadRequest);
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
