package com.dtech.algo.runner.candle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * STOMP message sent to /topic/bars/{symbol}/{interval} on every tick update.
 *
 * isPartial=true  → live tick (bar still forming, update existing bar on chart)
 * isPartial=false → bar just closed (final OHLC, advance to next bar)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BarUpdateMessage {
    private String symbol;
    private String interval;
    private long   time;       // epoch seconds (bar start)
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private boolean isPartial;
}
