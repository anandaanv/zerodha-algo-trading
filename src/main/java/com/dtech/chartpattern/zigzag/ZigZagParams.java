package com.dtech.chartpattern.zigzag;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ZigZagParams {
    public enum Mode { LIVE, BACKTEST }

    int atrLength;
    double atrMult;          // ATR multiplier for reversal confirmation
    double pctMin;           // Static percent floor (optional)
    double hysteresis;       // Additional multiplier for reversal vs. extension
    int minBarsBetweenPivots;
    boolean dynamicPctEnabled;
    double volMult;          // Multiplier for EMA(TR/close)
    int rvolWindow;          // Window for relative volatility EMA
    Mode mode;

    public static ZigZagParams ofDefaults(int atrLen, double atrMult, double pctMin,
                                          double hysteresis, int minBars,
                                          boolean dynamicPctEnabled, double volMult, int rvolWindow,
                                          Mode mode) {
        return ZigZagParams.builder()
                .atrLength(atrLen)
                .atrMult(atrMult)
                .pctMin(pctMin)
                .hysteresis(hysteresis)
                .minBarsBetweenPivots(minBars)
                .dynamicPctEnabled(dynamicPctEnabled)
                .volMult(volMult)
                .rvolWindow(rvolWindow)
                .mode(mode)
                .build();
    }
}
