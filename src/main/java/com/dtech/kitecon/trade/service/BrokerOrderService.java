package com.dtech.kitecon.trade.service;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.entity.TradeExecution;
import com.dtech.kitecon.trade.entity.TradeSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;

/**
 * Abstraction over broker (Kite Connect) order management.
 *
 * DRY RUN behaviour (trade.monitor.dryrun=true):
 *   - fetchLtp() reads latest candle close from the database as a price proxy.
 *   - placeMarketOrder / placeExitOrder are NOT called in dry run; the handlers
 *     simulate fills directly.
 *
 * LIVE behaviour (trade.monitor.dryrun=false):
 *   - TODO: replace fetchLtp with Kite LTP API.
 *   - TODO: implement placeMarketOrder and placeExitOrder via Kite Connect SDK.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BrokerOrderService {

    private final InstrumentRepository instrumentRepository;
    private final ZigZagService zigZagService;

    /**
     * Returns the latest available price for the symbol.
     * Uses the latest 1h candle close from DB as a proxy (dry run / backtesting).
     * Replace the body with a Kite LTP call for live trading.
     */
    public BigDecimal fetchLtp(String symbol, Long instrumentToken) {
        try {
            Instrument instrument = instrumentRepository
                    .findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE", "NFO"});
            if (instrument == null) {
                log.warn("[BrokerOrderService] Instrument not found for symbol={}", symbol);
                return null;
            }
            BarSeries series = zigZagService.getBarSeries(symbol, instrument, Interval.OneHour);
            if (series == null || series.isEmpty()) {
                log.warn("[BrokerOrderService] No bar data for symbol={}", symbol);
                return null;
            }
            double lastClose = series.getLastBar().getClosePrice().doubleValue();
            log.debug("[BrokerOrderService] fetchLtp({}): DB last close = {}", symbol, lastClose);
            return BigDecimal.valueOf(lastClose);
        } catch (Exception e) {
            log.warn("[BrokerOrderService] fetchLtp failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Places a market entry order at the broker.
     * TODO: implement via Kite Connect SDK.
     *
     * Example Kite implementation:
     *   KiteConnect kite = kiteConnectPool.getConnection();
     *   OrderParams params = new OrderParams();
     *   params.tradingsymbol = signal.getSymbol();
     *   params.exchange = signal.getExchange();
     *   params.orderType = "MARKET";
     *   params.transactionType = signal.getDirection() == LONG ? "BUY" : "SELL";
     *   params.quantity = signal.getLotSize();
     *   params.product = "MIS";  // or NRML for overnight
     *   return kite.placeOrder(params, "regular").orderId;
     */
    public String placeMarketOrder(TradeSignal signal) {
        throw new UnsupportedOperationException(
                "Live order placement not implemented. Set trade.monitor.dryrun=true for simulation.");
    }

    /**
     * Places a market exit order at the broker.
     * TODO: implement via Kite Connect SDK.
     */
    public String placeExitOrder(TradeSignal signal, TradeExecution execution) {
        throw new UnsupportedOperationException(
                "Live exit order not implemented. Set trade.monitor.dryrun=true for simulation.");
    }

    /**
     * Checks whether a broker order has been fully filled.
     * TODO: implement via Kite order history API.
     *
     * Example:
     *   KiteConnect kite = kiteConnectPool.getConnection();
     *   Order order = kite.getOrderHistory(orderId).get(0);
     *   return "COMPLETE".equals(order.status);
     */
    public boolean isOrderFilled(String orderId) {
        throw new UnsupportedOperationException(
                "Order status check not implemented. Set trade.monitor.dryrun=true for simulation.");
    }
}
