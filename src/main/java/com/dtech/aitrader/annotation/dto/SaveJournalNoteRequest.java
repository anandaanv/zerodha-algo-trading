package com.dtech.aitrader.annotation.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class SaveJournalNoteRequest {
    private String symbol;
    /** When the trader's thought is dated. Defaults to today server-side when missing. */
    private LocalDate noteDate;
    private String noteText;
}
