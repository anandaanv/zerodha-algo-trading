package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.trade.dto.QuoteResult;

public interface MarketQuoteService {
    QuoteResult getQuote(String symbol, Long instrumentToken);
}
