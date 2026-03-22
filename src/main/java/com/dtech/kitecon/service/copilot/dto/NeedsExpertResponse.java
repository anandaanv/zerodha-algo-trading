package com.dtech.kitecon.service.copilot.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/** AI has reached a decision point it cannot resolve without human input. */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class NeedsExpertResponse extends AIResponse {
    /** Unique question ID for tracking acknowledgment */
    private String questionId;
    /** The actual question text to show in the chat window */
    private String questionText;
    /** Additional context to help the expert answer */
    private String context;
    /** Suggested options to present as buttons in the chat, e.g. ["Confirm Wave 4", "Relabel as Wave 2"] */
    private List<String> options;
    /** WAVE_COUNT | PATTERN_CONFIRM | AMBIGUITY | OVERRIDE_REQUEST */
    private String questionType;
    /** Whether answering this blocks all further analysis (true) or is advisory (false) */
    private boolean blocking;
}
