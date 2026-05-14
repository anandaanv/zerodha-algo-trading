package com.dtech.aitrader.annotation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AnnotationBundleDto {
    private SymbolThesisDto thesis;
    private List<DrawingAnnotationDto> annotations;

    public boolean isEmpty() {
        boolean noThesis = thesis == null
                || (thesis.getThesisText() == null || thesis.getThesisText().isBlank())
                && thesis.getBias() == null && thesis.getRegime() == null;
        boolean noAnn = annotations == null || annotations.isEmpty();
        return noThesis && noAnn;
    }
}
