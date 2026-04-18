package com.dtech.kitecon.trade.strategy;

import com.dtech.kitecon.trade.entity.TradeSignal;
import java.math.BigDecimal;

public interface ExitStrategy {
    ExitDecision evaluate(TradeSignal signal, BigDecimal currentLtp, BigDecimal underlyingLtp);
}
