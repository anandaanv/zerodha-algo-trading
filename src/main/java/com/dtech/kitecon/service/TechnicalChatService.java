package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.dtech.kitecon.service.ai.OpenAIProviderService;
import com.dtech.kitecon.service.ai.tools.ValidationInput;
import com.dtech.kitecon.service.model.TechnicalChatRequest;
import com.dtech.kitecon.service.model.TechnicalChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TechnicalChatService {

    private final DrawingExtractorService drawingExtractor;
    private final ChartDataService chartDataService;
    private final OpenAIProviderService openAI;

    public TechnicalChatResponse chat(TechnicalChatRequest req) {
        List<ValidationInput.Drawing> drawings =
            drawingExtractor.extractDrawings(req.getChartStateJson());

        String intervalName = Interval.fromUiKey(req.getTimeframe()).name();
        List<OhlcBarDTO> bars = chartDataService.getBars(
            req.getSymbol(), intervalName, null, null);

        log.info("Technical chat: symbol={}, timeframe={}, drawings={}, bars={}, mode={}",
            req.getSymbol(), req.getTimeframe(), drawings.size(), bars.size(), req.getMode());

        String message = openAI.technicalChatAnalysis(
            req.getSymbol(), req.getTimeframe(),
            drawings, bars, req.getUserMessage(), req.getMode());

        return TechnicalChatResponse.builder()
            .message(message)
            .drawingCount(drawings.size())
            .mode(req.getMode())
            .build();
    }
}
