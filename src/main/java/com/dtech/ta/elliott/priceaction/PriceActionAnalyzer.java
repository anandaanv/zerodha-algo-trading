package com.dtech.ta.elliott.priceaction;

import com.dtech.ta.BarTuple;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import java.util.ArrayList;
import java.util.List;

public class PriceActionAnalyzer {

    private BarSeries series;
    private RSIIndicator rsiIndicator;
    private MACDIndicator macdIndicator;

    public PriceActionAnalyzer(BarSeries series) {
        this.series = series;
        this.rsiIndicator = new RSIIndicator(new ClosePriceIndicator(series), 14);  // 14-period RSI
        this.macdIndicator = new MACDIndicator(new ClosePriceIndicator(series), 12, 26);  // MACD 12,26
    }

    public List<PriceAction> analyzePriceActions(List<BarTuple> localExtremes) {
        List<PriceAction> priceActions = new ArrayList<>();

        for (int i = 0; i < localExtremes.size() - 3; i++) {
            BarTuple A = localExtremes.get(i);
            BarTuple B = localExtremes.get(i + 1);
            BarTuple C = localExtremes.get(i + 2);
            BarTuple D = localExtremes.get(i + 3);

            // Identify the direction of A-B, B-C, and C-D
            String directionAB = (B.getValue() > A.getValue()) ? "up" : "down";
            String directionBC = (C.getValue() > B.getValue()) ? "up" : "down";
            String directionCD = (D.getValue() > C.getValue()) ? "up" : "down";

            // Analyze A-B as an initial trend and B-C as a retracement
            if (!directionAB.equals(directionBC)) {
                // C-D is the continuation of A-B
                boolean isContinuation = isValidContinuation(A.getValue(), B.getValue(), C.getValue(), D.getValue());

                // Capture RSI and MACD values for C-D
                double relevantRSI = (directionCD.equals("up")) ? findHighestRSI(C.getIndex(), D.getIndex()) : findLowestRSI(C.getIndex(), D.getIndex());
                double relevantMACD = (directionCD.equals("up")) ? findHighestMACD(C.getIndex(), D.getIndex()) : findLowestMACD(C.getIndex(), D.getIndex());

                // Create and add the PriceAction object for the continuation C-D
                PriceAction priceAction = new PriceAction(directionCD, C, D,
                        C.getIndex(), D.getIndex(),
                        relevantRSI, relevantMACD, !isContinuation);
                priceActions.add(priceAction);
            }
        }

        return priceActions;
    }

    // Method to calculate Fibonacci extension levels from A-B
    private double[] calculateFibonacciLevels(double pointA, double pointB) {
        double distance = pointB - pointA;
        double[] fibonacciLevels = new double[4];
        fibonacciLevels[0] = pointB;  // 100% level (point B)
        fibonacciLevels[1] = pointB + distance * 0.272;  // 127.2% level
        fibonacciLevels[2] = pointB + distance * 0.618;  // 161.8% level
        fibonacciLevels[3] = pointB + distance * 1.0;    // 200% level
        return fibonacciLevels;
    }

    // Method to determine if the movement C-D is a valid continuation based on A-B
    private boolean isValidContinuation(double pointA, double pointB, double pointC, double pointD) {
        double[] fibonacciLevels = calculateFibonacciLevels(pointA, pointB);

        // Check if C-D extends beyond key Fibonacci levels of A-B
        return pointD > fibonacciLevels[1];  // Beyond 127.2% of A-B means strong continuation
    }

    // Find the highest RSI value between start and end indices (for uptrend)
    private double findHighestRSI(int startIndex, int endIndex) {
        double highestRSI = Double.MIN_VALUE;
        for (int i = startIndex; i <= endIndex; i++) {
            Num rsiValue = rsiIndicator.getValue(i);
            if (rsiValue.doubleValue() > highestRSI) {
                highestRSI = rsiValue.doubleValue();
            }
        }
        return highestRSI;
    }

    // Find the lowest RSI value between start and end indices (for downtrend)
    private double findLowestRSI(int startIndex, int endIndex) {
        double lowestRSI = Double.MAX_VALUE;
        for (int i = startIndex; i <= endIndex; i++) {
            Num rsiValue = rsiIndicator.getValue(i);
            if (rsiValue.doubleValue() < lowestRSI) {
                lowestRSI = rsiValue.doubleValue();
            }
        }
        return lowestRSI;
    }

    // Find the highest MACD value between start and end indices (for uptrend)
    private double findHighestMACD(int startIndex, int endIndex) {
        double highestMACD = Double.MIN_VALUE;
        for (int i = startIndex; i <= endIndex; i++) {
            Num macdValue = macdIndicator.getValue(i);
            if (macdValue.doubleValue() > highestMACD) {
                highestMACD = macdValue.doubleValue();
            }
        }
        return highestMACD;
    }

    // Find the lowest MACD value between start and end indices (for downtrend)
    private double findLowestMACD(int startIndex, int endIndex) {
        double lowestMACD = Double.MAX_VALUE;
        for (int i = startIndex; i <= endIndex; i++) {
            Num macdValue = macdIndicator.getValue(i);
            if (macdValue.doubleValue() < lowestMACD) {
                lowestMACD = macdValue.doubleValue();
            }
        }
        return lowestMACD;
    }
}
