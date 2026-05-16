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
public class TrendlineEvaluator implements DrawingEvaluator {

    @Override
    public boolean supports(String drawingType) {
        return "trendline".equalsIgnoreCase(drawingType);
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

        long t0 = p0.getTimestamp();
        long t1 = p1.getTimestamp();
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

        int touches = 0;
        int reversalsAfterTouch = 0;
        int breaks = 0;
        double totalReversalPct = 0.0;
        int reversalCount = 0;

        boolean isSupport = inferLineOrientation(drawing.getPoints(), bars);

        for (int i = 0; i < bars.size(); i++) {
            Candle bar = bars.get(i);
            double linePrice = priceAt(t0, price0, t1, price1, bar.epochSec());

            if (isTouch(bar.close(), linePrice, config.getTouchTolerancePct())) {
                touches++;

                double reversalPct = checkReversal(bars, i, linePrice, isSupport, config);
                if (reversalPct >= config.getMinReversalPct()) {
                    reversalsAfterTouch++;
                    totalReversalPct += reversalPct;
                    reversalCount++;
                }
            }

            if (isBreak(bars, i, linePrice, isSupport, config)) {
                breaks++;
            }
        }

        double avgReversalPct = reversalCount > 0 ? totalReversalPct / reversalCount : 0.0;
        double score = touches > 0 ? (double) reversalsAfterTouch / touches : 0.0;
        score = Math.min(score, 1.0);

        metrics.put("touches", touches);
        metrics.put("breaks", breaks);
        metrics.put("reversalsAfterTouch", reversalsAfterTouch);
        metrics.put("avgReversalPct", avgReversalPct);

        return DrawingScanResponse.DrawingResult.builder()
            .id(id)
            .type(drawing.getType())
            .score(score)
            .metrics(metrics)
            .build();
    }

    private double priceAt(long t0, double p0, long t1, double p1, long t) {
        if (t0 == t1) return p0;
        return p0 + (p1 - p0) * (double) (t - t0) / (t1 - t0);
    }

    private boolean isTouch(double close, double linePrice, double tolerancePct) {
        double tol = linePrice * tolerancePct / 100.0;
        return Math.abs(close - linePrice) <= tol;
    }

    private boolean inferLineOrientation(List<ValidationInput.Point> points, List<Candle> bars) {
        int aboveCount = 0;
        int belowCount = 0;

        ValidationInput.Point p0 = points.get(0);
        ValidationInput.Point p1 = points.get(1);
        long t0 = p0.getTimestamp();
        long t1 = p1.getTimestamp();
        double price0 = p0.getPrice();
        double price1 = p1.getPrice();

        for (Candle bar : bars) {
            if (bar.epochSec() < t0 || bar.epochSec() > t1) continue;
            double linePrice = priceAt(t0, price0, t1, price1, bar.epochSec());
            if (bar.close() > linePrice) aboveCount++;
            else if (bar.close() < linePrice) belowCount++;
        }

        return aboveCount > belowCount;
    }

    private double checkReversal(List<Candle> bars, int touchIdx, double linePrice, boolean isSupport, ScanConfig config) {
        double maxMove = 0.0;
        int lookahead = Math.min(config.getReversalLookaheadBars(), bars.size() - touchIdx - 1);

        for (int j = touchIdx + 1; j <= touchIdx + lookahead && j < bars.size(); j++) {
            Candle bar = bars.get(j);
            double move = isSupport ? bar.high() - linePrice : linePrice - bar.low();
            maxMove = Math.max(maxMove, move);
        }

        return maxMove > 0 ? (maxMove / linePrice) * 100.0 : 0.0;
    }

    private boolean isBreak(List<Candle> bars, int idx, double linePrice, boolean isSupport, ScanConfig config) {
        int confirmBars = 0;
        for (int j = idx; j < bars.size() && j < idx + config.getBreakConfirmBars(); j++) {
            Candle bar = bars.get(j);
            if (isSupport && bar.close() < linePrice) confirmBars++;
            else if (!isSupport && bar.close() > linePrice) confirmBars++;
        }
        return confirmBars >= config.getBreakConfirmBars();
    }
}
