package com.dtech.trade.zerodha;

import com.dtech.kitecon.data.Instrument;
import com.dtech.trade.order.RealTradeOrder;
import com.zerodhatech.models.Order;
import lombok.RequiredArgsConstructor;

import java.time.ZonedDateTime;

@RequiredArgsConstructor
public class ZerodhaOrder implements RealTradeOrder {

    private final Order order;
    private final Instrument instrument;

    @Override
    public String getExchangeOrderId() {
        return order.exchangeOrderId;
    }

    @Override
    public String getStatus() {
        return order.status;
    }

    @Override
    public String getStatusMessage() {
        return order.statusMessage;
    }

    @Override
    public double getAveragePrice() {
        return Double.parseDouble(order.averagePrice);
    }

    @Override
    public Integer getFulfilledQuantity() {
        return Integer.parseInt(order.filledQuantity);
    }

    @Override
    public ZonedDateTime getTimestamp() {
        return ZonedDateTime.from(order.exchangeUpdateTimestamp.toInstant());
    }

    @Override
    public Integer getDisclosedQuantity() {
        return Integer.parseInt(order.disclosedQuantity);
    }

    @Override
    public String getValidity() {
        return order.validity;
    }

    @Override
    public Instrument getInstrument() {
        return instrument;
    }

    @Override
    public String getOrderVariety() {
        return order.orderVariety;
    }

    @Override
    public String getUserId() {
        return order.accountId;
    }

    @Override
    public String getOrderType() {
        return order.orderType;
    }

    @Override
    public double getTriggerPrice() {
        return Double.parseDouble(order.triggerPrice);
    }

    @Override
    public Double getPrice() {
        return Double.parseDouble(order.price);
    }

    @Override
    public String getProduct() {
        return order.product;
    }

    @Override
    public String getAccountId() {
        return order.accountId;
    }

    @Override
    public String getExchange() {
        return order.exchange;
    }

    @Override
    public String getOrderId() {
        return order.orderId;
    }

    @Override
    public String getSymbol() {
        return order.product;
    }

    @Override
    public Integer getPendingQuantity() {
        return Integer.parseInt(order.pendingQuantity);
    }

    @Override
    public ZonedDateTime getOrderTimestamp() {
        return ZonedDateTime.from(order.orderTimestamp.toInstant());
    }

    @Override
    public ZonedDateTime getExchangeTimestamp() {
        return ZonedDateTime.from(order.exchangeUpdateTimestamp.toInstant());
    }

    @Override
    public ZonedDateTime getExchangeUpdateTimestamp() {
        return ZonedDateTime.from(order.exchangeUpdateTimestamp.toInstant());
    }

    @Override
    public String getTransactionType() {
        return order.transactionType;
    }

    @Override
    public Integer getQuantity() {
        return Integer.valueOf(order.quantity);
    }

    @Override
    public String getParentOrderId() {
        return order.parentOrderId;
    }
}
