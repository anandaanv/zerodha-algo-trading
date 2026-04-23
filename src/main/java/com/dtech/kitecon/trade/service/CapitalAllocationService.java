package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.market.facade.MarketFacadeProvider;
import com.zerodhatech.models.Margin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Slf4j
@RequiredArgsConstructor
public class CapitalAllocationService {

    private final MarketFacadeProvider marketFacadeProvider;

    @Value("${trade.portfolio.value:500000}")
    private long fallbackPortfolioValue;

    public int computeQuantity(BigDecimal capitalPct, BigDecimal price, int lotSize) {
        double availableCapital = getAvailableCapital();
        double allocationPct = capitalPct != null ? capitalPct.doubleValue() : 30.0;
        double capital = availableCapital * allocationPct / 100.0;

        int maxUnits = (int) (capital / price.doubleValue());

        int qty;
        if (lotSize <= 1) {
            qty = Math.max(1, maxUnits);
        } else {
            int numLots = Math.max(1, maxUnits / lotSize);
            qty = numLots * lotSize;
        }

        log.info("CapitalAllocation: available={} allocationPct={}% capital={} price={} lotSize={} → qty={}",
                String.format("%.2f", availableCapital), allocationPct, String.format("%.2f", capital), price, lotSize, qty);
        return qty;
    }

    private double getAvailableCapital() {
        try {
            Margin margin = marketFacadeProvider.getFacade().getMargins("equity");
            double cash = Double.parseDouble(margin.available.cash);
            log.info("CapitalAllocation: fetched available cash from Kite: {}", cash);
            return cash;
        } catch (Exception e) {
            log.warn("CapitalAllocation: failed to fetch margins, using fallback portfolioValue={}: {}",
                    fallbackPortfolioValue, e.getMessage());
            return fallbackPortfolioValue;
        }
    }
}
