package com.dtech.kitecon.service;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.DailyCandle;
import com.dtech.kitecon.data.FifteenMinuteCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.BaseCandleRepository;
import com.dtech.kitecon.repository.DailyCandleRepository;
import com.dtech.kitecon.repository.FifteenMinuteCandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Profile;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class DataFetchService {

    private final InstrumentRepository instrumentRepository;
    private final FifteenMinuteCandleRepository fifteenMinuteCandleRepository;
    private final DailyCandleRepository dailyCandleRepository;
    private final KiteConnectConfig kiteConnectConfig;

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
                this.downloadFifteenMinutesData(instrument, repository, interval);
            } catch (KiteException e) {
                System.out.println("Instrument - " + instrument.getTradingsymbol());
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void downloadFifteenMinutesData(Instrument instrument, BaseCandleRepository repository, String interval) throws KiteException, IOException {
        ZoneId defaultZoneId = ZoneId.systemDefault();
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

        LocalDate endDate = getEndDate();
        LocalDate startDate = getStartDate();

        // If loading for first time, then we need to use maximum available date range
        LocalDate startDateFirstTime = getMaxAvailableDateRange(instrument);

        BaseCandle latestCandle = repository.findFirstByInstrumentOrderByTimestampDesc(instrument);
        BaseCandle oldestCandle = repository.findFirstByInstrumentOrderByTimestamp(instrument);

        LocalDate actualStartDate = startDateFirstTime;
        if (oldestCandle != null && oldestCandle.getTimestamp().isBefore(startDate.atStartOfDay())) {
            actualStartDate = latestCandle.getTimestamp().toLocalDate();
            repository.delete(latestCandle);
        } else {
            repository.deleteByInstrument(instrument);
        }

        HistoricalData candles = kiteConnectConfig.getKiteConnect().getHistoricalData(Date.from(
            actualStartDate.atStartOfDay(defaultZoneId).toInstant()),
            Date.from(endDate.atStartOfDay(defaultZoneId).toInstant()),
                String.valueOf(instrument.getInstrument_token()), interval, false, true);
        List<BaseCandle> databaseCandles = candles.dataArrayList.stream().map(candle ->
        {
            try {
                return buildCandle(instrument, dateFormat, candle, interval);
            } catch (ParseException e) {
                e.printStackTrace();
                return null;
            }
        })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        repository.saveAll(databaseCandles);
    }

    private LocalDate getMaxAvailableDateRange(Instrument instrument) {
        LocalDate today = LocalDate.now();
        if (instrument.getExchange().equals("NFO")) {
            return today.minus(22, ChronoUnit.MONTHS);
        }
        return today.minus(60, ChronoUnit.DAYS);
    }

    private LocalDate getEndDate() {
        return LocalDate.now();
    }

    private LocalDate getStartDate() {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minus(139, ChronoUnit.DAYS);
        return startDate;
    }

    public BaseCandle buildCandle(Instrument instrument, DateTimeFormatter dateFormat, HistoricalData candle,
                                           String interval) throws ParseException {
        BaseCandle dbCandle;
        if(interval.equalsIgnoreCase("day")) {
            dbCandle = DailyCandle.builder().build();
        } else {
            dbCandle = FifteenMinuteCandle.builder().build();
        }
        dbCandle.setInstrument(instrument);
        dbCandle.setOpen(candle.open);
        dbCandle.setHigh(candle.high);
        dbCandle.setLow(candle.low);
        dbCandle.setClose(candle.close);
        dbCandle.setVolume(candle.volume);
        dbCandle.setOi(candle.oi);
        dbCandle.setTimestamp(ZonedDateTime.parse(candle.timeStamp, dateFormat).toLocalDateTime());
        return dbCandle;
    }

}
