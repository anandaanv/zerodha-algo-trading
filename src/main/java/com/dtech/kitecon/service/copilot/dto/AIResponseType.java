package com.dtech.kitecon.service.copilot.dto;

/**
 * The six structured response types that every AI call in the Co-Pilot system returns.
 * Application reads this type and routes accordingly.
 * AI never takes action — it only declares findings and needs.
 */
public enum AIResponseType {
    /** AI requires additional data before it can continue reasoning */
    NEEDS_DATA,
    /** AI has reached a decision point requiring human input */
    NEEDS_EXPERT,
    /** AI has identified something meaningful — pattern, stage, wave context, opportunity */
    FINDING,
    /** AI has determined entry conditions are met for a hypothesis */
    ENTRY_SIGNAL,
    /** Entry conditions not yet fully met — monitoring continues */
    MONITORING,
    /** A hypothesis invalidation condition has been triggered */
    INVALIDATED
}
