package com.dtech.algo.openai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

@Entity
@Table(name = "openai_conversation",
       uniqueConstraints = @UniqueConstraint(columnNames = {"symbol","timeframe"}))
@Data
public class OpenAiConversation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String symbol;

  private String timeframe;

  // OpenAI conversation identifier (conv_...)
  private String openaiThreadId;

  // Last response id from OpenAI
  private String openaiResponseId;

  // Persisted analysis JSON for quick reuse
  @Column(columnDefinition = "json")
  private String analysisJson;
}
