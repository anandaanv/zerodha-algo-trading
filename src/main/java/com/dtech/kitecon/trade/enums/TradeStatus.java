package com.dtech.kitecon.trade.enums;

public enum TradeStatus {
    WATCHING_ENTRY,   // signal generated, waiting for price to hit entry level
    ENTRY_PENDING,    // entry order placed at broker, waiting for fill
    ACTIVE,           // position open, monitoring for exit
    EXIT_PENDING,     // exit order placed at broker, waiting for fill
    COMPLETED,        // trade closed (win or loss)
    EXPIRED,          // entry window passed without triggering
    FAILED,           // broker order rejected / error
    CANCELLED         // manually cancelled
}
