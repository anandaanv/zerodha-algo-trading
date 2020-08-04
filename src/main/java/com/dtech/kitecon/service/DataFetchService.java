package com.dtech.kitecon.service;

import com.dtech.kitecon.controller.KiteConnectConfig;
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
                        .expiry(instrument.expiry)
                        .tick_size(instrument.tick_size)
                        .lot_size(instrument.lot_size)
                        .instrument_type(instrument.instrument_type)
                        .segment(instrument.segment)
                        .exchange(instrument.exchange)
                        .build()).collect(Collectors.toList());
        instrumentRepository.saveAll(newInstruments);
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
        Calendar today1 = Calendar.getInstance();
        today1.add(Calendar.MONTH, 4);
        List<Instrument> instruments = instrumentRepository
                .findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
                        instrumentName, Calendar.getInstance().getTime(), today1.getTime(), exchanges);
        List<Instrument> primaryInstruments = instrumentRepository
                .findAllByTradingsymbolStartingWithAndExpiryIsNullAndExchangeIn(
                        instrumentName, exchanges);
        instruments.addAll(primaryInstruments);
        instruments.addAll(primaryInstruments);
//        instruments.forEach(instrument -> {
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
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
        Calendar today = Calendar.getInstance();
        Date endDate = today.getTime();
        today.add(Calendar.DAY_OF_YEAR, -139);
        Date startDate = today.getTime();
        today.add(Calendar.DAY_OF_YEAR, -60);
        Date startDateFirstTime = today.getTime();
        if (instrument.getExchange().equals("NFO")) {
            today.add(Calendar.MONTH, 22);
            startDateFirstTime = today.getTime();
        }
        BaseCandle latestCandle = repository.findFirstByInstrumentOrderByTimestampDesc(instrument);
        BaseCandle oldestCandle = repository.findFirstByInstrumentOrderByTimestamp(instrument);
        if (oldestCandle != null && oldestCandle.getTimestamp().before(startDate)) {
            startDateFirstTime = latestCandle.getTimestamp();
            repository.delete(latestCandle);
        } else {
            repository.deleteByInstrument(instrument);
        }
        HistoricalData candles = kiteConnectConfig.getKiteConnect().getHistoricalData(startDateFirstTime, endDate,
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

    public BaseCandle buildCandle(Instrument instrument, DateFormat dateFormat, HistoricalData candle,
                                           String interval) throws ParseException {
        if(interval.equalsIgnoreCase("day")) {
            return DailyCandle.builder()
                    .instrument(instrument)
                    .open(candle.open)
                    .high(candle.high)
                    .low(candle.low)
                    .close(candle.close)
                    .volume(candle.volume)
                    .oi(candle.oi)
                    .timestamp(dateFormat.parse(candle.timeStamp))
                    .build();
        }
        return FifteenMinuteCandle.builder()
                .instrument(instrument)
                .open(candle.open)
                .high(candle.high)
                .low(candle.low)
                .close(candle.close)
                .volume(candle.volume)
                .oi(candle.oi)
                .timestamp(dateFormat.parse(candle.timeStamp))
                .build();
    }

}
