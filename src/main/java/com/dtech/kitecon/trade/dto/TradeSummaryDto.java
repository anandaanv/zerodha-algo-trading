package com.dtech.kitecon.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSummaryDto {
    private int totalTrades;
    private int openTrades;
    private int winCount;
    private int lossCount;
    private BigDecimal totalPnlInr;
    private BigDecimal avgWinInr;
    private BigDecimal avgLossInr;
    private BigDecimal winRatePct;
}
