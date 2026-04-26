package com.dtech.kitecon.market.api;

public class BrokerException extends Exception {
    public BrokerException(String message) { super(message); }
    public BrokerException(String message, Throwable cause) { super(message, cause); }
    public BrokerException(Throwable cause) { super(cause); }
}
