package com.dtech.kitecon.trade.strategy;

import com.dtech.kitecon.trade.enums.StrategyType;
import com.dtech.kitecon.trade.enums.TradingSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExitStrategyRouter {

    private final DtbExitStrategy dtbExitStrategy;
    private final ImpulseSlabExitStrategy impulseSlabExitStrategy;
    private final ImpulseSlabExitStrategyOpt impulseSlabExitStrategyOpt;

    public ExitStrategy getStrategy(StrategyType type) {
        return getStrategy(type, TradingSegment.EQ);
    }

    public ExitStrategy getStrategy(StrategyType type, TradingSegment segment) {
        return switch (type) {
            case DTB -> dtbExitStrategy;
            case IMPULSE -> segment == TradingSegment.OPT
                    ? impulseSlabExitStrategyOpt
                    : impulseSlabExitStrategy;
            default -> dtbExitStrategy;
        };
    }
}
