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
public class UnknownEvaluator implements DrawingEvaluator {

    @Override
    public boolean supports(String drawingType) {
        return true;
    }

    @Override
    public DrawingScanResponse.DrawingResult evaluate(
        ValidationInput.Drawing drawing,
        List<Candle> bars,
        ScanConfig config
    ) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("unsupported", true);

        return DrawingScanResponse.DrawingResult.builder()
            .id(id)
            .type(drawing.getType())
            .score(0.0)
            .metrics(metrics)
            .build();
    }
}
