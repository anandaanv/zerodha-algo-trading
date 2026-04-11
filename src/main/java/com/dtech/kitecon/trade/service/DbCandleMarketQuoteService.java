package com.dtech.kitecon.trade.service;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.dto.QuoteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;

@Service("dbCandleMarketQuoteService")
@Slf4j
@RequiredArgsConstructor
public class DbCandleMarketQuoteService implements MarketQuoteService {

    private final InstrumentRepository instrumentRepository;
    private final ZigZagService zigZagService;

    @Override
    public QuoteResult getQuote(String symbol, Long instrumentToken) {
        try {
            Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(
                    symbol, new String[]{"NSE", "NFO"});
            if (instrument == null) {
                instrument = instrumentRepository.findById(instrumentToken).orElse(null);
            }
            if (instrument == null) {
                log.warn("Instrument not found for symbol={} token={}", symbol, instrumentToken);
                return null;
            }

            BarSeries barSeries = zigZagService.getBarSeries(symbol, instrument, Interval.OneHour);
            if (barSeries == null || barSeries.getBarCount() == 0) {
                log.warn("No bar series data for symbol={}", symbol);
                return null;
            }

            BigDecimal lastClose = BigDecimal.valueOf(barSeries.getLastBar().getClosePrice().doubleValue());

            return QuoteResult.builder()
                    .symbol(symbol)
                    .instrumentToken(instrumentToken)
                    .ltp(lastClose)
                    .askPrice(lastClose)
                    .bidPrice(lastClose)
                    .build();

        } catch (Exception e) {
            log.warn("Error fetching quote for symbol={}: {}", symbol, e.getMessage());
            return null;
        }
    }
}
