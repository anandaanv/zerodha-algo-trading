package com.dtech.kitecon.trade.strategy;

import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.ExitReason;
import com.dtech.kitecon.trade.enums.TradeDirection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class DtbExitStrategy implements ExitStrategy {
    @Override
    public ExitDecision evaluate(TradeSignal signal, ExitContext ctx) {
        BigDecimal underlyingLtp = ctx.underlyingLtp();
        BigDecimal currentLtp = ctx.ltp();
        boolean isLong = signal.getDirection() == TradeDirection.LONG;
        if (isLong) {
            if (underlyingLtp.compareTo(signal.getStopLoss()) <= 0) return ExitDecision.exit(ExitReason.STOP_HIT, currentLtp);
            if (underlyingLtp.compareTo(signal.getTarget()) >= 0) return ExitDecision.exit(ExitReason.TARGET_HIT, currentLtp);
        } else {
            if (underlyingLtp.compareTo(signal.getStopLoss()) >= 0) return ExitDecision.exit(ExitReason.STOP_HIT, currentLtp);
            if (underlyingLtp.compareTo(signal.getTarget()) <= 0) return ExitDecision.exit(ExitReason.TARGET_HIT, currentLtp);
        }
        return ExitDecision.hold();
    }
}
