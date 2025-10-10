package com.dtech.algo.screener.resolver;

import com.dtech.algo.series.InstrumentType;
import lombok.Getter;

/**
 * Nomination for an option relative to spot.
 * offset: positive or negative integer according to the naming convention.
 * - For this project convention:
 *     CE1, CE2, CE3 are OTM calls (strikes above spot)
 *     CE-1, CE-2 are ITM calls (strikes below spot)
 *     PE1, PE2, PE3 are OTM puts (strikes below spot)
 *     PE-1, PE-2 are ITM puts (strikes above spot)
 *   The offset value carries the numeric part (can be negative).
 */
@Getter
public class OptionNomination {
    private final InstrumentType type;
    private final int offset;

    public OptionNomination(InstrumentType type, int offset) {
        this.type = type;
        this.offset = offset;
    }

    @Override
    public String toString() {
        return type.name() + (offset >= 0 ? String.valueOf(offset) : String.valueOf(offset));
    }
}
