package com.dtech.kitecon.trade.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class ResolvedInstrument {
    private String tradingSymbol;
    private Long instrumentToken;
    private Integer lotSize;
    private String instrumentType;
    private LocalDate expiry;
    private BigDecimal strike;
}
