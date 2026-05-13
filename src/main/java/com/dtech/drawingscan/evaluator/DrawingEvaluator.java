package com.dtech.drawingscan.evaluator;

import com.dtech.drawingscan.dto.DrawingScanResponse;
import com.dtech.drawingscan.model.Candle;
import com.dtech.drawingscan.model.ScanConfig;
import com.dtech.kitecon.service.ai.tools.ValidationInput;

import java.util.List;

public interface DrawingEvaluator {
    boolean supports(String drawingType);

    DrawingScanResponse.DrawingResult evaluate(
        ValidationInput.Drawing drawing,
        List<Candle> bars,
        ScanConfig config
    );
}
