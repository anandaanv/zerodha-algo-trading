package com.dtech.trade.model;

public enum OrderStatus {
    CREATED,
    SENT_TO_EXCHANGE,
    PARTIAL_COMPLETE,
    COMPLETE,
    FAILED,
    EXITED
}
