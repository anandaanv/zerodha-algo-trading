package com.dtech.ta.elliott;

import com.dtech.ta.BarTuple;
import com.dtech.ta.elliott.priceaction.PriceAction;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Delegate;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Wave {

    private int waveNumber;  // 1 to 5, or A, B, C for corrective waves
    @Delegate
    private PriceAction pa;  // Average volume during the wave

    // Convenience methods
    public boolean isUpward() {
        return pa.isUpward();
    }

    public boolean isDownward() {
        return !pa.isUpward();
    }
}
