package com.dtech.aitrader.annotation.service;

import com.dtech.aitrader.annotation.dto.AnnotationBundleDto;
import com.dtech.aitrader.annotation.dto.DrawingAnnotationDto;
import com.dtech.aitrader.annotation.dto.JournalNoteDto;
import com.dtech.aitrader.annotation.entity.ChartDrawingAnnotation;
import com.dtech.aitrader.annotation.entity.SymbolJournalNote;
import com.dtech.aitrader.annotation.repository.ChartDrawingAnnotationRepository;
import com.dtech.aitrader.annotation.repository.SymbolJournalNoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * Builds an AnnotationBundle for injection into AI agent prompts.
 * <p>
 * Two scopes:
 * - {@link #buildForLevels(Long, String, String)} — tab-scoped (Levels run is initiated from a chart on a specific tab).
 * - {@link #buildForPattern(Long, String)} — symbol-scoped (Pattern agent fires from system events, no tab context).
 * <p>
 * Renders to a Markdown prompt fragment via {@link #toPromptSection(AnnotationBundleDto)}. The fragment includes a
 * chronological "Trader's Journal" of free-form notes plus a priority-ranked list of drawing annotations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnnotationBundleBuilder {

    private static final int MAX_ANNOTATIONS_IN_PROMPT = 25;
    private static final int MAX_JOURNAL_NOTES = 30;
    private static final int MAX_NOTE_CHARS = 200;

    private final ChartDrawingAnnotationRepository drawingRepo;
    private final SymbolJournalNoteRepository journalRepo;

    @Transactional(readOnly = true)
    public AnnotationBundleDto buildForLevels(Long userId, String symbol, String tabUuid) {
        if (userId == null || symbol == null) {
            return AnnotationBundleDto.builder()
                    .annotations(Collections.emptyList())
                    .journalNotes(Collections.emptyList())
                    .build();
        }
        if (tabUuid == null || tabUuid.isBlank()) {
            return buildForPattern(userId, symbol);
        }
        List<ChartDrawingAnnotation> rows = drawingRepo
                .findByUserIdAndTabUuidAndSymbolAndActiveTrueOrderByWeightDescUpdatedAtDesc(userId, tabUuid, symbol);
        List<SymbolJournalNote> notes = journalRepo
                .findByUserIdAndSymbolOrderByNoteDateDescCreatedAtDesc(userId, symbol);
        return assemble(rows, notes);
    }

    @Transactional(readOnly = true)
    public AnnotationBundleDto buildForPattern(Long userId, String symbol) {
        if (userId == null || symbol == null) {
            return AnnotationBundleDto.builder()
                    .annotations(Collections.emptyList())
                    .journalNotes(Collections.emptyList())
                    .build();
        }
        List<ChartDrawingAnnotation> rows = drawingRepo
                .findByUserIdAndSymbolAndActiveTrueOrderByWeightDescUpdatedAtDesc(userId, symbol);
        List<SymbolJournalNote> notes = journalRepo
                .findByUserIdAndSymbolOrderByNoteDateDescCreatedAtDesc(userId, symbol);
        return assemble(rows, notes);
    }

    private AnnotationBundleDto assemble(List<ChartDrawingAnnotation> rows, List<SymbolJournalNote> notes) {
        List<DrawingAnnotationDto> annDtos = rows.stream().map(this::toDto).toList();
        List<JournalNoteDto> journalDtos = notes.stream().map(this::toDto).toList();
        return AnnotationBundleDto.builder()
                .annotations(annDtos)
                .journalNotes(journalDtos)
                .build();
    }

    /**
     * Renders the bundle to a Markdown prompt section. Returns an empty string when the bundle is empty so
     * callers can unconditionally append the result.
     */
    public String toPromptSection(AnnotationBundleDto bundle) {
        if (bundle == null || bundle.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Trader's Context\n\n");
        sb.append("These are the trader's notes and chart markings. Treat them as high-prior signals — they reflect ")
                .append("the trader's evolving view on this symbol. When a trade idea aligns with a journal note or ")
                .append("an annotation, increase confidence and reference the source in the rationale. When you propose ")
                .append("a trade that ignores them, justify why.\n\n");

        // Journal — chronological dated notes (newest first)
        List<JournalNoteDto> journal = bundle.getJournalNotes();
        if (journal != null && !journal.isEmpty()) {
            sb.append("### Trader's Journal (newest first)\n");
            int n = Math.min(journal.size(), MAX_JOURNAL_NOTES);
            DateTimeFormatter f = DateTimeFormatter.ISO_DATE;
            for (int i = 0; i < n; i++) {
                JournalNoteDto jn = journal.get(i);
                String date = jn.getNoteDate() == null ? "no-date" : jn.getNoteDate().format(f);
                String text = jn.getNoteText() == null ? "" : jn.getNoteText().trim();
                sb.append("- ").append(date).append(": ").append(text).append("\n");
            }
            if (journal.size() > MAX_JOURNAL_NOTES) {
                sb.append("(…").append(journal.size() - MAX_JOURNAL_NOTES).append(" older notes omitted)\n");
            }
            sb.append("\n");
        }

        // Drawing annotations — priority ordered by weight
        List<DrawingAnnotationDto> anns = bundle.getAnnotations();
        if (anns != null && !anns.isEmpty()) {
            sb.append("### Drawing Annotations (in priority order, weight 1-5)\n");
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

        sb.append("### How to use this context\n");
        sb.append("- Journal entries are timestamped — older notes provide context; recent notes are leading signals (e.g. \"earnings tomorrow\", \"watching for third wave\").\n");
        sb.append("- KEY_LEVEL / INVALIDATION / TARGET annotations are anchors for entry/stop/target computation.\n");
        sb.append("- RETEST_ENTRY → propose a trade when price is approaching the level within tolerance.\n");
        sb.append("- BREAKOUT_CONFIRM → wait for `confirmation_bars` beyond the level.\n");
        sb.append("- REJECT_ON_TOUCH → propose a fade trade at first touch.\n");
        sb.append("- OVERTHROW_WATCH → don't enter on immediate breakout; wait for settle inside the pattern.\n");
        sb.append("- ABC_PROJECTION → listed ratios are valid C-leg completion targets.\n");
        sb.append("- Free-text drawing note → the trader's intent for that specific line. Read literally and respect it.\n\n");

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
