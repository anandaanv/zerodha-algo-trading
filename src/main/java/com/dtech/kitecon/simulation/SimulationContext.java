package com.dtech.kitecon.simulation;

import com.dtech.kitecon.trade.entity.TradeSignal;
import lombok.Data;
import java.util.*;

@Data
public class SimulationContext {
    private SimulationClock clock;
    private final String strategyType;  // "IMPULSE", "DTB", etc.
    private final List<String> symbols;
    private final String timeframe;     // "FifteenMinute", "OneHour", etc.

    // Live state
    private final List<TradeSignal> openPositions = new ArrayList<>();
    private final List<TradeSignal> closedPositions = new ArrayList<>();
    private int totalSignalsGenerated = 0;
    private int totalSteps = 0;

    // Stats
    private double totalPnlPct = 0;
    private int wins = 0;
    private int losses = 0;
}
