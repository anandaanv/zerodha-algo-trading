package com.dtech.kitecon.trade.service;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.facade.MarketFacadeProvider;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.dto.QuoteResult;
import com.zerodhatech.models.Quote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;
import java.util.Map;

@Service("dbCandleMarketQuoteService")
@Slf4j
@RequiredArgsConstructor
public class DbCandleMarketQuoteService implements MarketQuoteService {

    private final InstrumentRepository instrumentRepository;
    private final ZigZagService zigZagService;
    private final MarketFacadeProvider marketFacadeProvider;

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

            // Try candle DB first (works for equity)
            BarSeries barSeries = zigZagService.getBarSeries(symbol, instrument, Interval.OneHour);
            if (barSeries != null && barSeries.getBarCount() > 0) {
                BigDecimal lastClose = BigDecimal.valueOf(barSeries.getLastBar().getClosePrice().doubleValue());
                return QuoteResult.builder()
                        .symbol(symbol)
                        .instrumentToken(instrumentToken)
                        .ltp(lastClose)
                        .askPrice(lastClose)
                        .bidPrice(lastClose)
                        .build();
            }

            // Fallback: live Kite quote (for options and instruments without candle data)
            return fetchLiveQuote(symbol, instrument);

        } catch (Exception e) {
            log.warn("Error fetching quote for symbol={}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private QuoteResult fetchLiveQuote(String symbol, Instrument instrument) {
        try {
            String exchange = instrument.getExchange() != null ? instrument.getExchange() : "NFO";
            String kiteSymbol = exchange + ":" + symbol;
            Map<String, Quote> quotes = marketFacadeProvider.getFacade().getQuote(new String[]{kiteSymbol});
            Quote quote = quotes.get(kiteSymbol);
            if (quote == null) {
                log.warn("No Kite quote for {}", kiteSymbol);
                return null;
            }

            BigDecimal ltp = BigDecimal.valueOf(quote.lastPrice);
            BigDecimal ask = quote.depth.sell.size() > 0
                    ? BigDecimal.valueOf(quote.depth.sell.get(0).getPrice())
                    : ltp;
            BigDecimal bid = quote.depth.buy.size() > 0
                    ? BigDecimal.valueOf(quote.depth.buy.get(0).getPrice())
                    : ltp;

            log.info("[LiveQuote] {} ltp={} bid={} ask={}", symbol, ltp, bid, ask);

            return QuoteResult.builder()
                    .symbol(symbol)
                    .instrumentToken(instrument.getInstrumentToken())
                    .ltp(ltp)
                    .askPrice(ask)
                    .bidPrice(bid)
                    .build();

        } catch (Exception e) {
            log.warn("Kite live quote failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
}
