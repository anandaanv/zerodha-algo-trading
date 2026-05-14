package com.dtech.aitrader.annotation.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "symbol_thesis",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_thesis_user_tab_symbol",
                columnNames = {"user_id", "tab_uuid", "symbol"}))
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SymbolThesis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tab_uuid", nullable = false, length = 64)
    private String tabUuid;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "bias", length = 16)
    private String bias;

    @Column(name = "regime", length = 32)
    private String regime;

    @Column(name = "horizon_days")
    private Integer horizonDays;

    @Lob
    @Column(name = "thesis_text", columnDefinition = "TEXT")
    private String thesisText;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }
}
