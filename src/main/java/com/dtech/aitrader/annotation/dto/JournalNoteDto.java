package com.dtech.aitrader.annotation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class JournalNoteDto {
    private Long id;
    private String symbol;
    private LocalDate noteDate;
    private String noteText;
    private Instant createdAt;
    private Instant updatedAt;
}
