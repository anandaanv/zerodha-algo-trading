package com.dtech.aitrader.annotation.service;

import com.dtech.aitrader.annotation.dto.DrawingAnnotationDto;
import com.dtech.aitrader.annotation.dto.SaveAnnotationRequest;
import com.dtech.aitrader.annotation.dto.SaveThesisRequest;
import com.dtech.aitrader.annotation.dto.SymbolThesisDto;
import com.dtech.aitrader.annotation.entity.ChartDrawingAnnotation;
import com.dtech.aitrader.annotation.entity.SymbolThesis;
import com.dtech.aitrader.annotation.repository.ChartDrawingAnnotationRepository;
import com.dtech.aitrader.annotation.repository.SymbolThesisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnnotationService {

    private final ChartDrawingAnnotationRepository drawingRepo;
    private final SymbolThesisRepository thesisRepo;

    @Transactional(readOnly = true)
    public List<DrawingAnnotationDto> listForChart(Long userId, String symbol, String tabUuid) {
        return drawingRepo
                .findByUserIdAndTabUuidAndSymbolAndActiveTrueOrderByWeightDescUpdatedAtDesc(userId, tabUuid, symbol)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<DrawingAnnotationDto> listForSymbol(Long userId, String symbol) {
        return drawingRepo
                .findByUserIdAndSymbolAndActiveTrueOrderByWeightDescUpdatedAtDesc(userId, symbol)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public DrawingAnnotationDto saveAnnotation(Long userId, SaveAnnotationRequest req) {
        if (req.getTabUuid() == null || req.getTabUuid().isBlank()) {
            throw new IllegalArgumentException("tabUuid required");
        }
        if (req.getSymbol() == null || req.getSymbol().isBlank()) {
            throw new IllegalArgumentException("symbol required");
        }
        if (req.getIntent() == null || req.getIntent().isBlank()) {
            throw new IllegalArgumentException("intent required");
        }
        String drawingId = (req.getDrawingId() == null || req.getDrawingId().isBlank())
                ? "client-" + UUID.randomUUID()
                : req.getDrawingId();

        ChartDrawingAnnotation existing = drawingRepo
                .findByUserIdAndTabUuidAndSymbolAndDrawingId(userId, req.getTabUuid(), req.getSymbol(), drawingId)
                .orElse(null);

        ChartDrawingAnnotation entity = existing != null ? existing : ChartDrawingAnnotation.builder()
                .userId(userId)
                .tabUuid(req.getTabUuid())
                .symbol(req.getSymbol())
                .drawingId(drawingId)
                .active(true)
                .build();
        entity.setInterval(req.getInterval());
        entity.setIntent(req.getIntent());
        entity.setIntentParamsJson(req.getIntentParamsJson());
        entity.setGeometryJson(req.getGeometryJson());
        entity.setNote(req.getNote());
        if (req.getWeight() != null) {
            entity.setWeight(Math.max(1, Math.min(5, req.getWeight())));
        } else if (entity.getWeight() == null) {
            entity.setWeight(3);
        }

        return toDto(drawingRepo.save(entity));
    }

    @Transactional
    public void deleteAnnotation(Long userId, Long annotationId) {
        ChartDrawingAnnotation row = drawingRepo.findById(annotationId)
                .orElseThrow(() -> new IllegalArgumentException("annotation not found: " + annotationId));
        if (!row.getUserId().equals(userId)) {
            throw new SecurityException("annotation belongs to another user");
        }
        drawingRepo.delete(row);
    }

    @Transactional(readOnly = true)
    public SymbolThesisDto getThesis(Long userId, String symbol, String tabUuid) {
        return thesisRepo.findByUserIdAndTabUuidAndSymbol(userId, tabUuid, symbol)
                .map(this::toDto)
                .orElse(null);
    }

    @Transactional
    public SymbolThesisDto saveThesis(Long userId, SaveThesisRequest req) {
        if (req.getTabUuid() == null || req.getTabUuid().isBlank()) {
            throw new IllegalArgumentException("tabUuid required");
        }
        if (req.getSymbol() == null || req.getSymbol().isBlank()) {
            throw new IllegalArgumentException("symbol required");
        }

        SymbolThesis entity = thesisRepo
                .findByUserIdAndTabUuidAndSymbol(userId, req.getTabUuid(), req.getSymbol())
                .orElse(SymbolThesis.builder()
                        .userId(userId)
                        .tabUuid(req.getTabUuid())
                        .symbol(req.getSymbol())
                        .build());
        entity.setBias(req.getBias());
        entity.setRegime(req.getRegime());
        entity.setHorizonDays(req.getHorizonDays());
        entity.setThesisText(req.getThesisText());

        return toDto(thesisRepo.save(entity));
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

    private SymbolThesisDto toDto(SymbolThesis e) {
        return SymbolThesisDto.builder()
                .id(e.getId())
                .tabUuid(e.getTabUuid())
                .symbol(e.getSymbol())
                .bias(e.getBias())
                .regime(e.getRegime())
                .horizonDays(e.getHorizonDays())
                .thesisText(e.getThesisText())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
