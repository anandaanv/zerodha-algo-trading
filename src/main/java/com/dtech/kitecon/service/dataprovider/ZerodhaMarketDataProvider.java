package com.dtech.kitecon.service.dataprovider;

import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.facade.MarketException;
import com.dtech.kitecon.market.facade.MarketFacade;
import com.dtech.kitecon.market.facade.MarketFacadeProvider;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Quote;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.num.DecimalNum;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Zerodha Kite Connect API-backed market data provider
 * Fetches data directly from Zerodha servers
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZerodhaMarketDataProvider implements MarketDataProvider {

    private final MarketFacadeProvider marketFacadeProvider;
    private final InstrumentRepository instrumentRepository;

    @Override
    public String getProviderName() {
        return "zerodha";
    }

    @Override
    public boolean isAvailable() {
        try {
            MarketFacade facade = marketFacadeProvider.getFacade();
            return facade != null && facade.isAvailable();
        } catch (Exception e) {
            log.warn("Zerodha provider not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public int getPriority() {
        return 10; // Higher priority (use Zerodha when available)
    }

    @Override
    public IntervalBarSeries loadBarSeries(BarSeriesConfig config) throws Exception {
        // TODO: Implement direct Zerodha API calls
        // For now, throw unsupported operation - use database provider instead
        throw new UnsupportedOperationException("Zerodha provider direct bar series loading not yet implemented. Use database provider.");
    }

    @Override
    public Quote getQuote(String symbol) throws Exception {
        try {
            Instrument instrument = resolveInstrument(symbol, "NSE");

            if (instrument == null) {
                throw new IllegalArgumentException("Instrument not found: " + symbol);
            }

            MarketFacade facade = marketFacadeProvider.getFacade();
            String[] instruments = {instrument.getExchange() + ":" + instrument.getTradingsymbol()};

            Map<String, Quote> quotes = facade.getQuote(instruments);
            return quotes.get(instrument.getExchange() + ":" + instrument.getTradingsymbol());
        } catch (MarketException e) {
            throw new Exception("Failed to get quote from Zerodha: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Quote> getQuotes(List<String> symbols) throws Exception {
        try {
            MarketFacade facade = marketFacadeProvider.getFacade();

            // Resolve all symbols to instruments
            String[] instruments = symbols.stream()
                .map(symbol -> {
                    Instrument inst = resolveInstrument(symbol, "NSE");
                    return inst != null ? inst.getExchange() + ":" + inst.getTradingsymbol() : null;
                })
                .filter(inst -> inst != null)
                .toArray(String[]::new);

            return facade.getQuote(instruments);
        } catch (MarketException e) {
            throw new Exception("Failed to get quotes from Zerodha: " + e.getMessage(), e);
        }
    }

    @Override
    public List<HistoricalData> getHistoricalData(
        Long instrumentToken,
        LocalDate from,
        LocalDate to,
        String interval,
        boolean continuous
    ) throws Exception {
        try {
            MarketFacade facade = marketFacadeProvider.getFacade();

            Date fromDate = Date.from(from.atStartOfDay(ZoneId.systemDefault()).toInstant());
            Date toDate = Date.from(to.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant());

            HistoricalData data = facade.getHistoricalData(fromDate, toDate, String.valueOf(instrumentToken), interval, continuous, false);
            return data != null && data.dataArrayList != null ? List.of(data) : List.of();
        } catch (MarketException e) {
            throw new Exception("Failed to get historical data from Zerodha: " + e.getMessage(), e);
        }
    }

    /**
     * Resolve instrument by symbol
     */
    private Instrument resolveInstrument(String symbol, String exchange) {
        return instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{exchange});
    }
}
