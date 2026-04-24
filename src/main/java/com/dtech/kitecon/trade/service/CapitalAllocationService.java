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
        int qty = Math.max(1, lotSize);
        log.info("CapitalAllocation: 1-lot mode — price={} lotSize={} → qty={}", price, lotSize, qty);
        return qty;
    }

    private double getAvailableCapital() {
        try {
            Margin margin = marketFacadeProvider.getFacade().getMargins("equity");
            // Use net balance (cash may be 0 after market hours, but net/liveBalance reflects actual funds)
            double balance = Double.parseDouble(margin.net);
            log.info("CapitalAllocation: fetched balance from Kite: net={} cash={} liveBalance={}",
                    margin.net, margin.available.cash, margin.available.liveBalance);
            return balance;
        } catch (Exception e) {
            log.warn("CapitalAllocation: failed to fetch margins, using fallback portfolioValue={}: {}",
                    fallbackPortfolioValue, e.getMessage());
            return fallbackPortfolioValue;
        }
    }
}
