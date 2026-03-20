package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.dtech.kitecon.service.ai.OpenAIProviderService;
import com.dtech.kitecon.service.ai.tools.ValidationInput;
import com.dtech.kitecon.service.model.MultiChartChatRequest;
import com.dtech.kitecon.service.model.MultiChartChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MultiChartChatService {

    private final DrawingExtractorService drawingExtractor;
    private final ChartDataService chartDataService;
    private final OpenAIProviderService openAI;

    public MultiChartChatResponse chat(MultiChartChatRequest req) {
        // Resolve each chart context into drawings + bars
        List<OpenAIProviderService.ResolvedChart> resolved = req.getCharts().stream()
            .map(ctx -> {
                List<ValidationInput.Drawing> drawings =
                    drawingExtractor.extractDrawings(ctx.getChartStateJson());
                String intervalName = Interval.fromUiKey(ctx.getTimeframe()).name();
                List<OhlcBarDTO> bars = chartDataService.getBars(
                    ctx.getSymbol(), intervalName, ctx.getVisibleFrom(), ctx.getVisibleTo());
                log.info("Multi-chart context '{}': symbol={}, drawings={}, bars={}",
                    ctx.getLabel(), ctx.getSymbol(), drawings.size(), bars.size());
                return new OpenAIProviderService.ResolvedChart(
                    ctx.getLabel(), ctx.getSymbol(), ctx.getTimeframe(), drawings, bars);
            })
            .toList();

        int totalDrawings = resolved.stream().mapToInt(c -> c.drawings().size()).sum();

        String message = openAI.multiChartAnalysis(resolved, req.getUserMessage(), req.getMode());

        return MultiChartChatResponse.builder()
            .message(message)
            .chartCount(resolved.size())
            .totalDrawingCount(totalDrawings)
            .mode(req.getMode())
            .build();
    }
}
