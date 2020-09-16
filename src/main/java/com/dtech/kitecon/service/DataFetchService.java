package com.dtech.kitecon.service;

import com.dtech.kitecon.config.HistoricalDateLimit;
import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.DailyCandle;
import com.dtech.kitecon.data.FifteenMinuteCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.historical.limits.LimitsKey;
import com.dtech.kitecon.repository.BaseCandleRepository;
import com.dtech.kitecon.repository.DailyCandleRepository;
import com.dtech.kitecon.repository.FifteenMinuteCandleRepository;
import com.dtech.kitecon.repository.FiveMinuteCandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Profile;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.io.IOException;
import java.text.ParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class DataFetchService {

    private final InstrumentRepository instrumentRepository;
    private final FifteenMinuteCandleRepository fifteenMinuteCandleRepository;
    private final FiveMinuteCandleRepository fiveMinuteCandleRepository;
    private final DailyCandleRepository dailyCandleRepository;
    private final KiteConnectConfig kiteConnectConfig;
    private final DataDownloader dataDownloader;
    private final HistoricalDateLimit historicalDateLimit;

    public String getProfile() throws IOException, KiteException {
        Profile profile = kiteConnectConfig.getKiteConnect().getProfile();
        return profile.userName;
    }

    public void downloadAllInstruments() throws KiteException, IOException {
        Map<Long, Instrument> databaseInstruments = instrumentRepository.findAll()
                .stream().collect(Collectors.toMap(Instrument::getInstrument_token, instrument -> instrument));
        List<com.zerodhatech.models.Instrument> instruments = kiteConnectConfig.getKiteConnect().getInstruments();
        List<Instrument> newInstruments = instruments.stream()
                .filter(instrument -> !databaseInstruments.containsKey(instrument.instrument_token))
                .map(instrument -> Instrument.builder()
                        .instrument_token(instrument.instrument_token)
                        .instrument_type(instrument.instrument_type)
                        .strike(instrument.strike)
                        .exchange_token(instrument.exchange_token)
                        .tradingsymbol(instrument.tradingsymbol)
                        .name(instrument.name)
                        .last_price(instrument.last_price)
                        .expiry(instrument.getExpiry() == null? null : dateToLocalDate(instrument))
                        .tick_size(instrument.tick_size)
                        .lot_size(instrument.lot_size)
                        .instrument_type(instrument.instrument_type)
                        .segment(instrument.segment)
                        .exchange(instrument.exchange)
                        .build()).collect(Collectors.toList());
        instrumentRepository.saveAll(newInstruments);
    }

    private LocalDateTime dateToLocalDate(com.zerodhatech.models.Instrument instrument) {
        return LocalDateTime.ofInstant(instrument.expiry.toInstant(),
            ZoneId.systemDefault());
    }

    @Transactional
    public void downloadHistoricalData15Mins(String instrumentName) {
        downloadCandleData(instrumentName, "15minute", fifteenMinuteCandleRepository);
    }

    @Transactional
    public void downloadHistoricalDataDaily(String instrumentName) {
        downloadCandleData(instrumentName, "day", dailyCandleRepository);
    }

    public void downloadCandleData(String instrumentName, String interval, BaseCandleRepository repository) {
        String[] exchanges = new String[]{"NSE", "NFO"};
        LocalDateTime startDate = LocalDateTime.now().minus(4, ChronoUnit.MONTHS);
        List<Instrument> instruments = instrumentRepository
                .findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
                        instrumentName, LocalDateTime.now(), startDate, exchanges);
        List<Instrument> primaryInstruments = instrumentRepository
                .findAllByTradingsymbolStartingWithAndExpiryIsNullAndExchangeIn(
                        instrumentName, exchanges);
        instruments.addAll(primaryInstruments);
        instruments.addAll(primaryInstruments);
        primaryInstruments.forEach(instrument -> {
            try {
                this.downloadData(instrument, repository, interval);
            } catch (KiteException e) {
                System.out.println("Instrument - " + instrument.getTradingsymbol());
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void downloadData(Instrument instrument, BaseCandleRepository repository, String interval) throws KiteException, IOException {
        ZoneId defaultZoneId = ZoneId.systemDefault();
        ZonedDateTime endTime = ZonedDateTime.now();
        ZonedDateTime startTime = endTime.minusYears(2);
        int days = historicalDateLimit.getDuration(instrument.getExchange(), interval);
        List<DateRange> dateRangeList = DateRange.builder()
            .endDate(endTime)
            .startDate(startTime)
            .build()
            .split(days);
        dateRangeList.forEach(dateRange -> {
            DataDownloadRequest dataDownloadRequest = DataDownloadRequest.builder()
                .dateRange(dateRange)
                .instrument(instrument)
                .interval(interval)
                .build();
            try {
                dataDownloader.processDownload(repository, dataDownloadRequest);
            } catch (KiteException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

  public void downloadHistoricalData5Mins(String instrument) {
      downloadCandleData(instrument, "5minute", fiveMinuteCandleRepository);
  }
}
