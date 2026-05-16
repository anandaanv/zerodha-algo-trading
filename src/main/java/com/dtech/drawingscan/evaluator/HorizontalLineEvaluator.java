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
public class HorizontalLineEvaluator implements DrawingEvaluator {

    @Override
    public boolean supports(String drawingType) {
        return "horizontal_line".equalsIgnoreCase(drawingType) || "horz_line".equalsIgnoreCase(drawingType);
    }

    @Override
    public DrawingScanResponse.DrawingResult evaluate(
        ValidationInput.Drawing drawing,
        List<Candle> bars,
        ScanConfig config
    ) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> metrics = new HashMap<>();

        if (drawing.getPoints() == null || drawing.getPoints().isEmpty()) {
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

        double linePrice = drawing.getPoints().get(0).getPrice();

        if (linePrice == 0) {
            metrics.put("invalid", true);
            return DrawingScanResponse.DrawingResult.builder()
                .id(id)
                .type(drawing.getType())
                .score(0.0)
                .metrics(metrics)
                .build();
        }

        int touches = 0;
        int holds = 0;
        int breaks = 0;

        for (int i = 0; i < bars.size(); i++) {
            Candle bar = bars.get(i);

            if (isTouch(bar.close(), linePrice, config.getTouchTolerancePct())) {
                touches++;

                double reversal = checkReversal(bars, i, linePrice, config);
                if (reversal >= config.getMinReversalPct()) {
                    holds++;
                }
            }

            if (isBreak(bars, i, linePrice, config)) {
                breaks++;
            }
        }

        double holdRate = touches > 0 ? (double) holds / touches : 0.0;

        metrics.put("touches", touches);
        metrics.put("holds", holds);
        metrics.put("breaks", breaks);
        metrics.put("holdRate", holdRate);

        return DrawingScanResponse.DrawingResult.builder()
            .id(id)
            .type(drawing.getType())
            .score(holdRate)
            .metrics(metrics)
            .build();
    }

    private boolean isTouch(double close, double linePrice, double tolerancePct) {
        double tol = linePrice * tolerancePct / 100.0;
        return Math.abs(close - linePrice) <= tol;
    }

    private double checkReversal(List<Candle> bars, int touchIdx, double linePrice, ScanConfig config) {
        double maxMove = 0.0;
        int lookahead = Math.min(config.getReversalLookaheadBars(), bars.size() - touchIdx - 1);

        for (int j = touchIdx + 1; j <= touchIdx + lookahead && j < bars.size(); j++) {
            Candle bar = bars.get(j);
            double move = Math.abs(bar.high() - linePrice) > Math.abs(bar.low() - linePrice)
                ? bar.high() - linePrice
                : linePrice - bar.low();
            maxMove = Math.max(maxMove, Math.abs(move));
        }

        return maxMove > 0 ? (maxMove / linePrice) * 100.0 : 0.0;
    }

    private boolean isBreak(List<Candle> bars, int idx, double linePrice, ScanConfig config) {
        int confirmBars = 0;
        for (int j = idx; j < bars.size() && j < idx + config.getBreakConfirmBars(); j++) {
            Candle bar = bars.get(j);
            if (bar.close() > linePrice) confirmBars++;
        }
        return confirmBars >= config.getBreakConfirmBars();
    }
}
