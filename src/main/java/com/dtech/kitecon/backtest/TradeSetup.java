package com.dtech.kitecon.backtest;

import lombok.Builder;
import lombok.Data;

import java.util.Locale;

@Data
@Builder
public class TradeSetup {
    private String datetime;
    private String symbol;
    private String patternType;
    private String confirmationType;
    private double level1Price;
    private double level2Price;
    private double neckline;
    private double fibRetracePct;
    private double patternHeight;
    private String reversalPattern;
    private double revCandleOpen;
    private double revCandleHigh;
    private double revCandleLow;
    private double revCandleClose;
    private double sigCandleOpen;
    private double sigCandleHigh;
    private double sigCandleLow;
    private double sigCandleClose;
    private double sigCandleVolume;
    private double entryPrice;
    private double stopLoss;
    private double target;
    private double riskPct;
    private double rewardPct;
    private double rrRatio;
    private double macdHistogram;
    private double macdSignal;
    private double stochRsiK;
    private double stochRsiD;
    private double bbWidth;
    private String bbContracting;
    private double dailyMacdHistogram;
    private double dailyRsi;
    private double dailyStochRsiK;
    private double rsiAtP1;
    private double rsiAtP2;
    private String result;
    private int barsToResult;
    private double pnlPct;
    private double exitPrice;
    private String exitReason;

    public String toCsvRow() {
        return String.format(Locale.US,
                "%s,%s,%s,%s," +
                "%.4f,%.4f,%.4f,%.2f,%.4f," +
                "%s,%.4f,%.4f,%.4f,%.4f," +
                "%.4f,%.4f,%.4f,%.4f,%.0f," +
                "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f," +
                "%.6f,%.6f,%.6f,%.6f,%.6f,%s," +
                "%.6f,%.4f,%.6f," +
                "%s,%d,%.4f,%.4f,%s,%.4f,%.4f",
                datetime, symbol, patternType, confirmationType,
                level1Price, level2Price, neckline, fibRetracePct, patternHeight,
                reversalPattern, revCandleOpen, revCandleHigh, revCandleLow, revCandleClose,
                sigCandleOpen, sigCandleHigh, sigCandleLow, sigCandleClose, sigCandleVolume,
                entryPrice, stopLoss, target, riskPct, rewardPct, rrRatio,
                macdHistogram, macdSignal, stochRsiK, stochRsiD, bbWidth, bbContracting,
                dailyMacdHistogram, dailyRsi, dailyStochRsiK,
                result, barsToResult, pnlPct, exitPrice, exitReason, rsiAtP1, rsiAtP2);
    }
}
