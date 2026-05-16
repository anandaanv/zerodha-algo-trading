package com.dtech.aitrader.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_trader_symbol")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTraderSymbol {
    @Id
    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false)
    private Boolean aiLevelsEnabled;

    @Column(nullable = false)
    private Boolean patternAgentEnabled;

    private LocalDateTime lastLevelsRunAt;
}
