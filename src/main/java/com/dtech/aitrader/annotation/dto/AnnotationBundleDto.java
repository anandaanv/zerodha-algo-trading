package com.dtech.aitrader.annotation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Bundle of trader context sent to the AI agents:
 *  - journalNotes: chronological free-text notes the trader has written
 *  - annotations:  per-drawing intent + note (RETEST_ENTRY, KEY_LEVEL, ...)
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AnnotationBundleDto {
    private List<JournalNoteDto> journalNotes;
    private List<DrawingAnnotationDto> annotations;

    public boolean isEmpty() {
        boolean noJournal = journalNotes == null || journalNotes.isEmpty();
        boolean noAnn = annotations == null || annotations.isEmpty();
        return noJournal && noAnn;
    }
}
