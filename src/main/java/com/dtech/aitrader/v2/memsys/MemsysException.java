package com.dtech.aitrader.v2.memsys;

/**
 * Thrown when a memsys MCP call fails. {@link #getCode()} carries the JSON-RPC error code
 * when the server returned a structured error (e.g. -32602 validation, -32603 internal),
 * or {@code 0} for transport-level failures.
 */
public class MemsysException extends RuntimeException {

    private final int code;

    public MemsysException(String message) {
        super(message);
        this.code = 0;
    }

    public MemsysException(String message, Throwable cause) {
        super(message, cause);
        this.code = 0;
    }

    public MemsysException(int code, String message) {
        super("memsys " + code + ": " + message);
        this.code = code;
    }

    public int getCode() { return code; }
}
