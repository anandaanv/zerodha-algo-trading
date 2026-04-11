package com.dtech.kitecon.trade.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class QuoteResult {
    private String symbol;
    private Long instrumentToken;
    private BigDecimal askPrice;
    private BigDecimal bidPrice;
    private BigDecimal ltp;
}
