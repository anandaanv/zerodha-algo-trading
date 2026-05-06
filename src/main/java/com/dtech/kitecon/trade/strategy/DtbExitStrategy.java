package com.dtech.kitecon.trade.strategy;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.ExitReason;
import com.dtech.kitecon.trade.enums.TradeDirection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;

@Component
@Slf4j
@RequiredArgsConstructor
public class DtbExitStrategy implements ExitStrategy {

    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;

    @Override
    public ExitDecision evaluate(TradeSignal signal, ExitContext ctx) {
        BigDecimal underlyingLtp = ctx.underlyingLtp();
        BigDecimal currentLtp = ctx.ltp();
        boolean isLong = signal.getDirection() == TradeDirection.LONG;

        // Stop-loss is evaluated on a CLOSING basis: only fires when the latest
        // completed bar at the signal's timeframe (e.g. hourly) closes through
        // the SL level. Wicks that pierce intra-bar are ignored.
        BigDecimal stopBasis = lastCompletedBarClose(signal);
        if (stopBasis != null) {
            if (isLong && stopBasis.compareTo(signal.getStopLoss()) <= 0) {
                return ExitDecision.exit(ExitReason.STOP_HIT, currentLtp);
            }
            if (!isLong && stopBasis.compareTo(signal.getStopLoss()) >= 0) {
                return ExitDecision.exit(ExitReason.STOP_HIT, currentLtp);
            }
        }

        // Target is evaluated on touch — lock in profit as soon as price reaches it.
        if (isLong && underlyingLtp.compareTo(signal.getTarget()) >= 0) {
            return ExitDecision.exit(ExitReason.TARGET_HIT, currentLtp);
        }
        if (!isLong && underlyingLtp.compareTo(signal.getTarget()) <= 0) {
            return ExitDecision.exit(ExitReason.TARGET_HIT, currentLtp);
        }
        return ExitDecision.hold();
    }

    private BigDecimal lastCompletedBarClose(TradeSignal signal) {
        try {
            String tf = signal.getTimeframe();
            if (tf == null) return null;
            Interval interval = Interval.fromUiKey(tf);
            Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(
                    signal.getSymbol(), new String[]{"NSE", "BSE"});
            if (instrument == null) return null;
            BarSeries series = zigZagService.getBarSeries(signal.getSymbol(), instrument, interval);
            if (series == null || series.getBarCount() == 0) return null;
            Bar lastBar = series.getLastBar();
            return BigDecimal.valueOf(lastBar.getClosePrice().doubleValue());
        } catch (Exception e) {
            log.debug("[DtbExit] Could not load last bar close for signal {} {}: {}",
                    signal.getId(), signal.getSymbol(), e.getMessage());
            return null;
        }
    }
}
