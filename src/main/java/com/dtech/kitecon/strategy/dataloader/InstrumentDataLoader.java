package com.dtech.kitecon.strategy.dataloader;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.ta4j.core.TimeSeries;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class InstrumentDataLoader {

    private final InstrumentRepository instrumentRepository;
    private final BarsLoader barsLoader;

    public Map<Instrument, TimeSeries> loadData(String instrumentName) {
        String[] exchanges = new String[]{"NSE", "NFO"};
        List<Instrument> instruments = getRelaventInstruments(instrumentName, exchanges);
        return instruments.stream()
                .collect(Collectors.toMap(instrument ->
                        instrument, instrument -> barsLoader.loadInstrumentSeries(instrument)));
    }

    private List<Instrument> getRelaventInstruments(String instrument, String[] exchanges) {
        return instrumentRepository
                .findAllByTradingsymbolStartingWithAndExchangeIn(instrument, exchanges);
    }

}
