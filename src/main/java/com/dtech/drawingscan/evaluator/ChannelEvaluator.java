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
public class ChannelEvaluator implements DrawingEvaluator {

    @Override
    public boolean supports(String drawingType) {
        return "channel".equalsIgnoreCase(drawingType);
    }

    @Override
    public DrawingScanResponse.DrawingResult evaluate(
        ValidationInput.Drawing drawing,
        List<Candle> bars,
        ScanConfig config
    ) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> metrics = new HashMap<>();

        if (drawing.getPoints() == null || drawing.getPoints().size() < 3) {
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

        List<ValidationInput.Point> points = drawing.getPoints();
        ValidationInput.Point top0 = points.get(0);
        ValidationInput.Point top1 = points.get(1);
        ValidationInput.Point bot0 = points.get(2);

        double topPrice0 = top0.getPrice();
        double topPrice1 = top1.getPrice();
        double botPrice0 = bot0.getPrice();

        if (topPrice0 == 0 || topPrice1 == 0 || botPrice0 == 0) {
            metrics.put("invalid", true);
            return DrawingScanResponse.DrawingResult.builder()
                .id(id)
                .type(drawing.getType())
                .score(0.0)
                .metrics(metrics)
                .build();
        }

        long t0 = top0.getTimestamp();
        long t1 = top1.getTimestamp();

        double offset = botPrice0 - topPrice0;
        double topPrice1Adjusted = topPrice1 + offset;

        int topTouches = 0;
        int topReversals = 0;
        int botTouches = 0;
        int botReversals = 0;
        int breakouts = 0;

        for (int i = 0; i < bars.size(); i++) {
            Candle bar = bars.get(i);
            double topLinePrice = priceAt(t0, topPrice0, t1, topPrice1, bar.epochSec());
            double botLinePrice = topLinePrice - offset;

            if (isTouch(bar.close(), topLinePrice, config.getTouchTolerancePct())) {
                topTouches++;
                double reversal = checkReversal(bars, i, topLinePrice, false, config);
                if (reversal >= config.getMinReversalPct()) {
                    topReversals++;
                }
            }

            if (isTouch(bar.close(), botLinePrice, config.getTouchTolerancePct())) {
                botTouches++;
                double reversal = checkReversal(bars, i, botLinePrice, true, config);
                if (reversal >= config.getMinReversalPct()) {
                    botReversals++;
                }
            }

            if (bar.high() > topLinePrice || bar.low() < botLinePrice) {
                breakouts++;
            }
        }

        int totalTouches = topTouches + botTouches;
        int totalReversals = topReversals + botReversals;
        double score = totalTouches > 0 ? (double) totalReversals / totalTouches : 0.0;
        score = Math.min(score, 1.0);

        metrics.put("topTouches", topTouches);
        metrics.put("topReversals", topReversals);
        metrics.put("botTouches", botTouches);
        metrics.put("botReversals", botReversals);
        metrics.put("breakouts", breakouts);

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
}
