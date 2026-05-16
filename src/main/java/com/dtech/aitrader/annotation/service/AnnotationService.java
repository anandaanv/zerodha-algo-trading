package com.dtech.aitrader.annotation.service;

import com.dtech.aitrader.annotation.dto.DrawingAnnotationDto;
import com.dtech.aitrader.annotation.dto.JournalNoteDto;
import com.dtech.aitrader.annotation.dto.SaveAnnotationRequest;
import com.dtech.aitrader.annotation.dto.SaveJournalNoteRequest;
import com.dtech.aitrader.annotation.entity.ChartDrawingAnnotation;
import com.dtech.aitrader.annotation.entity.SymbolJournalNote;
import com.dtech.aitrader.annotation.repository.ChartDrawingAnnotationRepository;
import com.dtech.aitrader.annotation.repository.SymbolJournalNoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnnotationService {

    private final ChartDrawingAnnotationRepository drawingRepo;
    private final SymbolJournalNoteRepository journalRepo;

    // ── Drawing annotations ─────────────────────────────────────────────

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

    // ── Journal notes ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<JournalNoteDto> listJournalNotes(Long userId, String symbol) {
        return journalRepo
                .findByUserIdAndSymbolOrderByNoteDateDescCreatedAtDesc(userId, symbol)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public JournalNoteDto addJournalNote(Long userId, SaveJournalNoteRequest req) {
        if (req.getSymbol() == null || req.getSymbol().isBlank()) {
            throw new IllegalArgumentException("symbol required");
        }
        if (req.getNoteText() == null || req.getNoteText().isBlank()) {
            throw new IllegalArgumentException("noteText required");
        }
        SymbolJournalNote entity = SymbolJournalNote.builder()
                .userId(userId)
                .symbol(req.getSymbol())
                .noteDate(req.getNoteDate() != null ? req.getNoteDate() : LocalDate.now())
                .noteText(req.getNoteText().trim())
                .build();
        return toDto(journalRepo.save(entity));
    }

    @Transactional
    public void deleteJournalNote(Long userId, Long noteId) {
        SymbolJournalNote row = journalRepo.findById(noteId)
                .orElseThrow(() -> new IllegalArgumentException("journal note not found: " + noteId));
        if (!row.getUserId().equals(userId)) {
            throw new SecurityException("journal note belongs to another user");
        }
        journalRepo.delete(row);
    }

    // ── Mappers ─────────────────────────────────────────────────────────

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

    private JournalNoteDto toDto(SymbolJournalNote e) {
        return JournalNoteDto.builder()
                .id(e.getId())
                .symbol(e.getSymbol())
                .noteDate(e.getNoteDate())
                .noteText(e.getNoteText())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
