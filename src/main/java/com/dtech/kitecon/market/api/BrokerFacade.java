package com.dtech.kitecon.market.api;

import com.zerodhatech.models.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Unified broker interface abstracting Zerodha and Dhan APIs.
 * Implementations wrap broker-specific SDKs behind a common contract.
 */
public interface BrokerFacade {

    BrokerType getType();

    String getBrokerName();

    boolean isAvailable();

    Map<String, Quote> getQuote(String[] instruments) throws BrokerException;

    Map<String, LTPQuote> getLTP(String[] instruments) throws BrokerException;

    OrderResponse placeOrder(OrderParams params, String variety) throws BrokerException;

    List<Instrument> getInstruments() throws BrokerException;

    HistoricalData getHistoricalData(Date from, Date to, String token,
                                      String interval, boolean continuous, boolean oi) throws BrokerException;
}
