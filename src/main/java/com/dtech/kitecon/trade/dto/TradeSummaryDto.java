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
    /** Realised PnL: sum across CLOSED orders. */
    private BigDecimal realisedPnlInr;
    /** Unrealised PnL: sum across OPEN orders using last LTP mark (null if no LTP). */
    private BigDecimal unrealisedPnlInr;
    /** realisedPnlInr + unrealisedPnlInr (treats null unrealised as zero). */
    private BigDecimal totalPnlInr;
    private BigDecimal avgWinInr;
    private BigDecimal avgLossInr;
    private BigDecimal winRatePct;
}
