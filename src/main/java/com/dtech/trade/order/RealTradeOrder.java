package com.dtech.trade.order;

import com.dtech.kitecon.data.Instrument;

import java.time.ZonedDateTime;

public interface RealTradeOrder {

    String getExchangeOrderId();

    String getStatus();

    String getStatusMessage();

    double getAveragePrice();

    Integer getFulfilledQuantity();

    ZonedDateTime getTimestamp();


    Integer getDisclosedQuantity();
    String getValidity();
    Instrument getInstrument();

    String getOrderVariety();
    String getUserId();
    String getOrderType();
    double getTriggerPrice();
    Double getPrice();
    String getProduct();
    String getAccountId();
    String getExchange();
    String getOrderId();
    String getSymbol();
    Integer getPendingQuantity();
    ZonedDateTime getOrderTimestamp();
    ZonedDateTime getExchangeTimestamp();
    ZonedDateTime getExchangeUpdateTimestamp();
    String getTransactionType();

    Integer getQuantity();

    String getParentOrderId();

}
