package com.dtech.ta.elliott.confluence;

import com.dtech.ta.elliott.swing.Swing;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class FibLevelBuilder {

    private static final double[] RETRACE_RATIOS  = {0.236, 0.382, 0.500, 0.618, 0.786};
    private static final double[] EXTENSION_RATIOS = {1.000, 1.272, 1.618, 2.000, 2.618};

    /**
     * Retracements measured from swing.endPrice back toward swing.startPrice.
     * For UP swing: retrace levels are below endPrice.
     * For DOWN swing: retrace levels are above endPrice.
     */
    public List<PriceLevel> buildRetracementLevels(Swing swing, double tolerancePct) {
        List<PriceLevel> levels = new ArrayList<>();
        double absChange = Math.abs(swing.getPriceChange());
        double tol = absChange * tolerancePct;
        boolean up = "UP".equals(swing.getDirection());

        for (double ratio : RETRACE_RATIOS) {
            double price = up
                    ? swing.getEndPrice() - ratio * absChange
                    : swing.getEndPrice() + ratio * absChange;
            String label = "FIBO_" + formatRatio(ratio) + "_RET";
            levels.add(PriceLevel.builder()
                    .price(price)
                    .label(label)
                    .sourceType("FIB_RETRACEMENT")
                    .tolerance(tol)
                    .build());
        }
        return levels;
    }

    /**
     * Extension targets projected from originPrice using baseSwing's range.
     * For UP baseSwing: extensions are above originPrice.
     * For DOWN baseSwing: extensions are below originPrice.
     */
    public List<PriceLevel> buildExtensionLevels(Swing baseSwing, double originPrice, double tolerancePct) {
        List<PriceLevel> levels = new ArrayList<>();
        double absChange = Math.abs(baseSwing.getPriceChange());
        double tol = absChange * tolerancePct;
        boolean up = "UP".equals(baseSwing.getDirection());

        for (double ratio : EXTENSION_RATIOS) {
            double price = up
                    ? originPrice + ratio * absChange
                    : originPrice - ratio * absChange;
            String label = "FIBO_" + formatRatio(ratio) + "_EXT";
            levels.add(PriceLevel.builder()
                    .price(price)
                    .label(label)
                    .sourceType("FIB_EXTENSION")
                    .tolerance(tol)
                    .build());
        }
        return levels;
    }

    private String formatRatio(double ratio) {
        // e.g. 0.236 -> "23.6", 1.618 -> "161.8"
        double pct = ratio * 100;
        if (pct == Math.floor(pct)) {
            return String.valueOf((int) pct);
        }
        return String.valueOf(Math.round(pct * 10) / 10.0);
    }
}
