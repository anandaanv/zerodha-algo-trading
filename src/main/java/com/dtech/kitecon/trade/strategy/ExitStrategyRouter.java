package com.dtech.kitecon.trade.strategy;

import com.dtech.kitecon.trade.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExitStrategyRouter {
    private final DtbExitStrategy dtbExitStrategy;
    private final ImpulseSlabExitStrategy impulseSlabExitStrategy;

    public ExitStrategy getStrategy(StrategyType type) {
        if (type == null) return dtbExitStrategy;  // backward compat
        return switch (type) {
            case DTB -> dtbExitStrategy;
            case IMPULSE -> impulseSlabExitStrategy;
        };
    }
}
