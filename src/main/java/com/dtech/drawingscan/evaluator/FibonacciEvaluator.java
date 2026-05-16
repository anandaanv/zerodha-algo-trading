package com.dtech.drawingscan.evaluator;

import com.dtech.drawingscan.dto.DrawingScanResponse;
import com.dtech.drawingscan.model.Candle;
import com.dtech.drawingscan.model.ScanConfig;
import com.dtech.kitecon.service.ai.tools.ValidationInput;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class FibonacciEvaluator implements DrawingEvaluator {

    private static final double[] FIB_LEVELS = {0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0};

    @Override
    public boolean supports(String drawingType) {
        return "fibonacci".equalsIgnoreCase(drawingType);
    }

    @Override
    public DrawingScanResponse.DrawingResult evaluate(
        ValidationInput.Drawing drawing,
        List<Candle> bars,
        ScanConfig config
    ) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> metrics = new HashMap<>();

        if (drawing.getPoints() == null || drawing.getPoints().size() < 2) {
            metrics.put("invalid", true);
            return DrawingScanResponse.DrawingResult.builder()
                .id(id)
                .type(drawing.getType())
                .score(0.0)
                .metrics(metrics)
                .build();
        }

        if (bars == null || bars.isEmpty()) {
            metrics.put("noBars", true);
            return DrawingScanResponse.DrawingResult.builder()
                .id(id)
                .type(drawing.getType())
                .score(0.0)
                .metrics(metrics)
                .build();
        }

        ValidationInput.Point p0 = drawing.getPoints().get(0);
        ValidationInput.Point p1 = drawing.getPoints().get(1);

        double price0 = p0.getPrice();
        double price1 = p1.getPrice();

        if (price0 == 0 || price1 == 0) {
            metrics.put("invalid", true);
            return DrawingScanResponse.DrawingResult.builder()
                .id(id)
                .type(drawing.getType())
                .score(0.0)
                .metrics(metrics)
                .build();
        }

        double range = price1 - price0;
        int totalRespected = 0;
        int totalTouches = 0;

        List<Map<String, Object>> levels = new java.util.ArrayList<>();

        for (double fib : FIB_LEVELS) {
            double fibPrice = price0 + range * fib;
            int touches = 0;
            int respected = 0;

            for (int i = 0; i < bars.size(); i++) {
                Candle bar = bars.get(i);
                if (isTouch(bar.close(), fibPrice, config.getTouchTolerancePct())) {
                    touches++;
                    totalTouches++;

                    double reversal = checkReversal(bars, i, fibPrice, config);
                    if (reversal >= config.getMinReversalPct()) {
                        respected++;
                        totalRespected++;
                    }
                }
            }

            Map<String, Object> levelMetrics = new HashMap<>();
            levelMetrics.put("level", fib);
            levelMetrics.put("touches", touches);
            levelMetrics.put("respected", respected);
            levels.add(levelMetrics);
        }

        double overallRespectRate = totalTouches > 0 ? (double) totalRespected / totalTouches : 0.0;

        metrics.put("levels", levels);
        metrics.put("overallRespectRate", overallRespectRate);

        return DrawingScanResponse.DrawingResult.builder()
            .id(id)
            .type(drawing.getType())
            .score(overallRespectRate)
            .metrics(metrics)
            .build();
    }

    private boolean isTouch(double close, double fibPrice, double tolerancePct) {
        double tol = fibPrice * tolerancePct / 100.0;
        return Math.abs(close - fibPrice) <= tol;
    }

    private double checkReversal(List<Candle> bars, int touchIdx, double fibPrice, ScanConfig config) {
        double maxMove = 0.0;
        int lookahead = Math.min(config.getReversalLookaheadBars(), bars.size() - touchIdx - 1);

        for (int j = touchIdx + 1; j <= touchIdx + lookahead && j < bars.size(); j++) {
            Candle bar = bars.get(j);
            double move = Math.abs(bar.high() - fibPrice) > Math.abs(bar.low() - fibPrice)
                ? bar.high() - fibPrice
                : fibPrice - bar.low();
            maxMove = Math.max(maxMove, Math.abs(move));
        }

        return maxMove > 0 ? (maxMove / fibPrice) * 100.0 : 0.0;
    }
}
