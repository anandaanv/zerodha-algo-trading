package com.dtech.aitrader.annotation.service;

import com.dtech.aitrader.annotation.dto.AnnotationBundleDto;
import com.dtech.aitrader.annotation.dto.DrawingAnnotationDto;
import com.dtech.aitrader.annotation.dto.SymbolThesisDto;
import com.dtech.aitrader.annotation.entity.ChartDrawingAnnotation;
import com.dtech.aitrader.annotation.entity.SymbolThesis;
import com.dtech.aitrader.annotation.repository.ChartDrawingAnnotationRepository;
import com.dtech.aitrader.annotation.repository.SymbolThesisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * Builds an AnnotationBundle for injection into AI agent prompts.
 * <p>
 * Two scopes:
 * - {@link #buildForLevels(Long, String, String)} — tab-scoped (Levels run is initiated from a chart on a specific tab).
 * - {@link #buildForPattern(Long, String)} — symbol-scoped (Pattern agent fires from system events, no tab context). Picks
 *   annotations across all tabs the user has, plus the most recent thesis row for the symbol.
 * <p>
 * Both also render the bundle to a prompt fragment via {@link #toPromptSection(AnnotationBundleDto)}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnnotationBundleBuilder {

    private static final int MAX_ANNOTATIONS_IN_PROMPT = 25;
    private static final int MAX_NOTE_CHARS = 200;

    private final ChartDrawingAnnotationRepository drawingRepo;
    private final SymbolThesisRepository thesisRepo;

    @Transactional(readOnly = true)
    public AnnotationBundleDto buildForLevels(Long userId, String symbol, String tabUuid) {
        if (userId == null || symbol == null) return AnnotationBundleDto.builder().annotations(Collections.emptyList()).build();
        if (tabUuid == null || tabUuid.isBlank()) {
            return buildForPattern(userId, symbol);
        }
        List<ChartDrawingAnnotation> rows = drawingRepo
                .findByUserIdAndTabUuidAndSymbolAndActiveTrueOrderByWeightDescUpdatedAtDesc(userId, tabUuid, symbol);
        SymbolThesis thesis = thesisRepo.findByUserIdAndTabUuidAndSymbol(userId, tabUuid, symbol).orElse(null);
        return assemble(rows, thesis);
    }

    @Transactional(readOnly = true)
    public AnnotationBundleDto buildForPattern(Long userId, String symbol) {
        if (userId == null || symbol == null) return AnnotationBundleDto.builder().annotations(Collections.emptyList()).build();
        List<ChartDrawingAnnotation> rows = drawingRepo
                .findByUserIdAndSymbolAndActiveTrueOrderByWeightDescUpdatedAtDesc(userId, symbol);
        SymbolThesis thesis = thesisRepo.findFirstByUserIdAndSymbolOrderByUpdatedAtDesc(userId, symbol).orElse(null);
        return assemble(rows, thesis);
    }

    private AnnotationBundleDto assemble(List<ChartDrawingAnnotation> rows, SymbolThesis thesis) {
        List<DrawingAnnotationDto> dtos = rows.stream().map(this::toDto).toList();
        SymbolThesisDto thesisDto = thesis == null ? null : SymbolThesisDto.builder()
                .id(thesis.getId())
                .tabUuid(thesis.getTabUuid())
                .symbol(thesis.getSymbol())
                .bias(thesis.getBias())
                .regime(thesis.getRegime())
                .horizonDays(thesis.getHorizonDays())
                .thesisText(thesis.getThesisText())
                .updatedAt(thesis.getUpdatedAt())
                .build();
        return AnnotationBundleDto.builder().thesis(thesisDto).annotations(dtos).build();
    }

    /**
     * Renders the bundle to a Markdown prompt section. Returns an empty string when the bundle is empty so
     * callers can unconditionally append the result.
     */
    public String toPromptSection(AnnotationBundleDto bundle) {
        if (bundle == null || bundle.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Trader's Annotations\n\n");
        sb.append("The trader has marked the following on this chart. Treat these as high-prior signals — ")
                .append("they reflect setups and levels the trader believes matter. Use them to weigh trade ideas; ")
                .append("when a trade idea aligns with an annotation, increase its confidence and reference the annotation ")
                .append("in the rationale. When you propose a trade that ignores an annotation, justify why.\n\n");

        SymbolThesisDto t = bundle.getThesis();
        if (t != null) {
            boolean has = (t.getThesisText() != null && !t.getThesisText().isBlank())
                    || t.getBias() != null || t.getRegime() != null || t.getHorizonDays() != null;
            if (has) {
                sb.append("### Symbol thesis\n");
                if (t.getBias() != null) sb.append("- Bias: ").append(t.getBias()).append("\n");
                if (t.getRegime() != null) sb.append("- Regime: ").append(t.getRegime()).append("\n");
                if (t.getHorizonDays() != null) sb.append("- Horizon (days): ").append(t.getHorizonDays()).append("\n");
                if (t.getThesisText() != null && !t.getThesisText().isBlank()) {
                    sb.append("- Trader's view: ").append(t.getThesisText().trim()).append("\n");
                }
                sb.append("\n");
            }
        }

        List<DrawingAnnotationDto> anns = bundle.getAnnotations();
        if (anns != null && !anns.isEmpty()) {
            sb.append("### Drawn annotations (in priority order, weight 1-5)\n");
            int n = Math.min(anns.size(), MAX_ANNOTATIONS_IN_PROMPT);
            for (int i = 0; i < n; i++) {
                DrawingAnnotationDto a = anns.get(i);
                sb.append(i + 1).append(". [").append(a.getIntent())
                        .append(" · weight ").append(a.getWeight() != null ? a.getWeight() : 3).append("]");
                if (a.getInterval() != null) sb.append(" (").append(a.getInterval()).append(")");
                sb.append("\n");
                if (a.getIntentParamsJson() != null && !a.getIntentParamsJson().isBlank()) {
                    sb.append("   Params: ").append(a.getIntentParamsJson().trim()).append("\n");
                }
                if (a.getGeometryJson() != null && !a.getGeometryJson().isBlank()) {
                    sb.append("   Geometry: ").append(a.getGeometryJson().trim()).append("\n");
                }
                if (a.getNote() != null && !a.getNote().isBlank()) {
                    String note = a.getNote().trim();
                    if (note.length() > MAX_NOTE_CHARS) note = note.substring(0, MAX_NOTE_CHARS) + "…";
                    sb.append("   Note: \"").append(note).append("\"\n");
                }
            }
            if (anns.size() > MAX_ANNOTATIONS_IN_PROMPT) {
                sb.append("(…").append(anns.size() - MAX_ANNOTATIONS_IN_PROMPT)
                        .append(" lower-weight annotations omitted)\n");
            }
            sb.append("\n");
        }

        sb.append("### How to act on these annotations\n");
        sb.append("- KEY_LEVEL / INVALIDATION / TARGET → use as anchors when computing entry/stop/target.\n");
        sb.append("- RETEST_ENTRY → if price is approaching the level within the tolerance, propose a trade keyed on reversal confirmation at the level.\n");
        sb.append("- BREAKOUT_CONFIRM → wait for `confirmation_bars` bars beyond the level before calling breakout.\n");
        sb.append("- REJECT_ON_TOUCH → propose a fade trade at first touch.\n");
        sb.append("- OVERTHROW_WATCH → do NOT enter on the immediate breakout. Wait for price to re-enter the pattern within `expected_settle_bars`; enter only on settle.\n");
        sb.append("- ABC_PROJECTION → the listed ratios are valid C-leg completion targets. Highlight which target current price action favors.\n");
        sb.append("- Free-text note → the trader's intent. Read literally and respect it.\n\n");

        return sb.toString();
    }

    private DrawingAnnotationDto toDto(ChartDrawingAnnotation e) {
        return DrawingAnnotationDto.builder()
                .id(e.getId())
                .tabUuid(e.getTabUuid())
                .symbol(e.getSymbol())
                .interval(e.getInterval())
                .drawingId(e.getDrawingId())
                .intent(e.getIntent())
                .intentParamsJson(e.getIntentParamsJson())
                .geometryJson(e.getGeometryJson())
                .note(e.getNote())
                .weight(e.getWeight())
                .active(e.isActive())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
