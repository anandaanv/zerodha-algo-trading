package com.dtech.algo.openai;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "openai_response", indexes = {
        @Index(name = "idx_response_conv_fk", columnList = "conversation_fk"),
        @Index(name = "idx_response_created_at", columnList = "createdAt")
})
@Getter
@Setter
@NoArgsConstructor
public class OpenAiResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // DB link to our conversation row
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_fk", nullable = false)
    private OpenAiConversation conversation;

    // Server-side conversation id (conv_...)
    @Column(length = 128, nullable = false)
    private String openaiConversationId;

    // Server response id, if present
    @Column(length = 128)
    private String openaiResponseId;

    // Raw response text (or JSON) for auditing/overlay
    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String responseText;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
