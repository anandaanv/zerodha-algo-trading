package com.dtech.kitecon.trade.strategy;

import com.dtech.kitecon.trade.enums.ExitReason;
import java.math.BigDecimal;

public record ExitDecision(
    Action action,
    ExitReason exitReason,   // only if action == EXIT
    BigDecimal exitPrice,     // only if action == EXIT
    Integer newSlabIndex      // only if action == UPDATE_SLAB
) {
    public enum Action { HOLD, EXIT, UPDATE_SLAB }

    public static ExitDecision hold() { return new ExitDecision(Action.HOLD, null, null, null); }
    public static ExitDecision exit(ExitReason reason, BigDecimal price) { return new ExitDecision(Action.EXIT, reason, price, null); }
    public static ExitDecision updateSlab(int newIndex) { return new ExitDecision(Action.UPDATE_SLAB, null, null, newIndex); }
}
