package com.dtech.kitecon.trade.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Slf4j
public class CapitalAllocationService {

    @Value("${trade.portfolio.value:500000}")
    private long portfolioValue;

    public int computeQuantity(BigDecimal capitalPct, BigDecimal price, int lotSize) {
        double capital = portfolioValue * capitalPct.doubleValue() / 100.0;
        int maxUnits = (int) (capital / price.doubleValue());

        int qty;
        if (lotSize <= 1) {
            qty = Math.max(1, maxUnits);
        } else {
            int numLots = Math.max(1, maxUnits / lotSize);
            qty = numLots * lotSize;
        }

        log.debug("CapitalAllocation: portfolioValue={} capitalPct={}% price={} lotSize={} → qty={}",
                portfolioValue, capitalPct, price, lotSize, qty);
        return qty;
    }
}
