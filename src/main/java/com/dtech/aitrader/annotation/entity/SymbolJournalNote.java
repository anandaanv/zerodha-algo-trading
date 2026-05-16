package com.dtech.aitrader.annotation.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A free-form, dated journal note the trader keeps against a symbol.
 *
 * Many notes per (user, symbol) are allowed — this is a chronological log,
 * not a structured opinion record. The AI agents read these alongside the
 * live data so the trader's evolving thinking ("might start a third wave soon",
 * "earnings tomorrow", "crossed 200dMA") feeds into decisions.
 */
@Entity
@Table(name = "symbol_journal_note",
        indexes = {
                @Index(name = "ix_journal_note_user_symbol_date",
                        columnList = "user_id, symbol, note_date")
        })
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SymbolJournalNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "note_date", nullable = false)
    private LocalDate noteDate;

    @Lob
    @Column(name = "note_text", nullable = false, columnDefinition = "TEXT")
    private String noteText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
