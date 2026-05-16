package com.dtech.drawingscan.service;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.dtech.drawingscan.dto.DrawingScanRequest;
import com.dtech.drawingscan.dto.DrawingScanResponse;
import com.dtech.drawingscan.evaluator.DrawingEvaluator;
import com.dtech.drawingscan.evaluator.UnknownEvaluator;
import com.dtech.drawingscan.model.Candle;
import com.dtech.drawingscan.model.ScanConfig;
import com.dtech.kitecon.data.UserChartState;
import com.dtech.kitecon.repository.UserChartStateRepository;
import com.dtech.kitecon.service.DrawingExtractorService;
import com.dtech.kitecon.service.ai.tools.ValidationInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DrawingScanService {

    private final UserChartStateRepository chartStateRepository;
    private final DrawingExtractorService drawingExtractorService;
    private final ChartDataService chartDataService;
    private final List<DrawingEvaluator> evaluators;
    private final UnknownEvaluator unknownEvaluator;
    private final ScanConfig scanConfig;

    public DrawingScanResponse scan(DrawingScanRequest request, String username) {
        DrawingScanResponse.Range range = DrawingScanResponse.Range.builder()
            .from(request.getFromSec())
            .to(request.getToSec())
            .build();

        List<DrawingScanResponse.DrawingResult> results = new ArrayList<>();

        UserChartState chartState = chartStateRepository
            .findBySymbolAndLayoutId(request.getSymbol(), 1L)
            .or(() -> chartStateRepository.findBySymbolAndLayoutId(request.getSymbol(), null))
            .orElse(null);

        if (chartState == null || chartState.getOverlaysJson() == null || chartState.getOverlaysJson().isEmpty()) {
            return DrawingScanResponse.builder()
                .symbol(request.getSymbol())
                .scanTf(request.getScanTf())
                .range(range)
                .drawings(results)
                .summary(DrawingScanResponse.Summary.builder()
                    .totalDrawings(0)
                    .bestId(null)
                    .bestScore(0.0)
                    .build())
                .build();
        }

        List<ValidationInput.Drawing> drawings = drawingExtractorService.extractDrawings(chartState.getOverlaysJson());

        List<OhlcBarDTO> bars = chartDataService.getBars(
            request.getSymbol(),
            request.getScanTf(),
            request.getFromSec(),
            request.getToSec(),
            false
        );

        List<Candle> candles = convertBarsToCandles(bars);

        for (ValidationInput.Drawing drawing : drawings) {
            DrawingScanResponse.DrawingResult result = evaluateDrawing(drawing, candles);
            if (result != null) {
                results.add(result);
            } else {
                log.warn("Evaluator returned null for drawing type: {}", drawing.getType());
            }
        }

        DrawingScanResponse.Summary summary = computeSummary(results);

        return DrawingScanResponse.builder()
            .symbol(request.getSymbol())
            .scanTf(request.getScanTf())
            .range(range)
            .drawings(results)
            .summary(summary)
            .build();
    }

    private List<Candle> convertBarsToCandles(List<OhlcBarDTO> bars) {
        List<Candle> candles = new ArrayList<>();
        for (OhlcBarDTO bar : bars) {
            candles.add(new Candle(bar.getTime(), bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose()));
        }
        return candles;
    }

    private DrawingScanResponse.DrawingResult evaluateDrawing(ValidationInput.Drawing drawing, List<Candle> candles) {
        for (DrawingEvaluator evaluator : evaluators) {
            if (evaluator == unknownEvaluator) continue;
            if (evaluator.supports(drawing.getType())) {
                return evaluator.evaluate(drawing, candles, scanConfig);
            }
        }
        return unknownEvaluator.evaluate(drawing, candles, scanConfig);
    }

    private DrawingScanResponse.Summary computeSummary(List<DrawingScanResponse.DrawingResult> results) {
        Optional<DrawingScanResponse.DrawingResult> best = results.stream()
            .max(Comparator.comparingDouble(DrawingScanResponse.DrawingResult::getScore));

        return DrawingScanResponse.Summary.builder()
            .totalDrawings(results.size())
            .bestId(best.map(DrawingScanResponse.DrawingResult::getId).orElse(null))
            .bestScore(best.map(DrawingScanResponse.DrawingResult::getScore).orElse(0.0))
            .build();
    }
}
