package com.dtech.aitrader.v2.memsys.auth;

public class MemsysOAuthException extends RuntimeException {
    public MemsysOAuthException(String message) { super(message); }
    public MemsysOAuthException(String message, Throwable cause) { super(message, cause); }
}
