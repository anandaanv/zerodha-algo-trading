package com.dtech.kitecon;

import com.dtech.kitecon.data.FifteenMinuteCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.FifteenMinuteCandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Profile;
import com.zerodhatech.models.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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
    private KiteConnect kiteConnect;

    public String getProfile() throws IOException, KiteException {
        Profile profile = kiteConnect.getProfile();
        return profile.userName;
    }

    //TODO export the credentials to configuration.
    @PostConstruct
    public void connect() throws KiteException, IOException {
        this.kiteConnect = new KiteConnect("1g19o5ohebi8fj3l");

        //If you wish to enable debug logs send true in the constructor, this will log request and response.
        //KiteConnect kiteConnect = new KiteConnect("xxxxyyyyzzzz", true);

        // If you wish to set proxy then pass proxy as a second parameter in the constructor with api_key. syntax:- new KiteConnect("xxxxxxyyyyyzzz", proxy).
        //KiteConnect kiteConnect = new KiteConnect("xxxxyyyyzzzz", userProxy, false);

        // Set userId
        kiteConnect.setUserId("ZQ5356");

        // Get login url
        String url = kiteConnect.getLoginURL();

        User user = kiteConnect.generateSession("SZG3F8IU4f7AfTkLD5v9zOdtQya0vus8", "47hfn5uwrb138506whg0lk26w6pxiadi");
        kiteConnect.setAccessToken(user.accessToken);
        kiteConnect.setPublicToken(user.publicToken);

    }

    public void downloadAllInstruments() throws KiteException, IOException {
        Map<Long, Instrument> databaseInstruments = instrumentRepository.findAll()
                .stream().collect(Collectors.toMap(Instrument::getInstrument_token, instrument -> instrument));
        List<com.zerodhatech.models.Instrument> instruments = kiteConnect.getInstruments();
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
    public void downloadHistoricalData15Mins(long instrumentId) throws KiteException, IOException {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
        Calendar today = Calendar.getInstance();
        Date endDate = today.getTime();
        today.add(Calendar.MONTH, -22);
        Date startDate = today.getTime();
        today.add(Calendar.MONTH, -2);
        Date startDateFirstTime = today.getTime();

        Instrument instrument = instrumentRepository.getOne(instrumentId);
        FifteenMinuteCandle latestCandle = fifteenMinuteCandleRepository.findFirstByInstrumentOrderByTimestampDesc(instrument);
        FifteenMinuteCandle oldestCandle = fifteenMinuteCandleRepository.findFirstByInstrumentOrderByTimestamp(instrument);
        if (oldestCandle != null && oldestCandle.getTimestamp().before(startDate)) {
            startDateFirstTime = latestCandle.getTimestamp();
            fifteenMinuteCandleRepository.delete(latestCandle);
        } else {
            fifteenMinuteCandleRepository.deleteByInstrument(instrument);
        }
        HistoricalData candles = kiteConnect.getHistoricalData(startDateFirstTime, endDate, String.valueOf(instrumentId), "15minute", false, true);
        List<FifteenMinuteCandle> databaseCandles = candles.dataArrayList.stream().map(candle ->
        {
            try {
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
            } catch (ParseException e) {
                e.printStackTrace();
                return null;
            }
        })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        fifteenMinuteCandleRepository.saveAll(databaseCandles);
    }

}
