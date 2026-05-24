package com.dtech.aitrader.v2.regime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict regime-record-v1 validator. Source of truth: memsys {@code a44c035a}.
 *
 * <p>The reader and the eval loop both run records through this. A record that fails validation
 * is REJECTED (never used for trade signals, never aggregated into hit-rate). The validator is
 * deliberately strict — owner rule: "Reject/log malformed records — do NOT trade on a record
 * that fails validation."
 */
@Component
@Slf4j
public class RegimeRecordValidator {

    public static final String EXPECTED_SCHEMA = "regime-record-v1";

    private final ObjectMapper json = JsonMapper.builder()
            .addModule(new ParameterNamesModule())
            .addModule(new JavaTimeModule())
            .build();

    /**
     * Parse + validate a memsys memory body string. Returns either a {@link Valid} result holding
     * the record, or an {@link Invalid} result with a list of human-readable errors. NEVER throws.
     */
    public Result parse(String memoryBody, String memoryId) {
        if (memoryBody == null || memoryBody.isBlank()) {
            return new Invalid(memoryId, List.of("empty body"));
        }
        RegimeRecord record;
        try {
            record = json.readValue(memoryBody, RegimeRecord.class);
        } catch (Exception e) {
            log.warn("[regime-validator] {} JSON parse failed: {}", memoryId, e.getMessage());
            return new Invalid(memoryId, List.of("json parse error: " + e.getMessage()));
        }
        List<String> errors = validate(record);
        if (errors.isEmpty()) return new Valid(memoryId, record);
        log.warn("[regime-validator] {} validation failed: {}", memoryId, errors);
        return new Invalid(memoryId, errors);
    }

    /**
     * Validate an already-deserialised record. Returns the list of errors (empty = valid). Each
     * error is a short human-readable string; the caller logs them.
     */
    public List<String> validate(RegimeRecord r) {
        List<String> errors = new ArrayList<>();
        if (r == null) {
            errors.add("record is null");
            return errors;
        }
        if (!EXPECTED_SCHEMA.equals(r.getSchema())) {
            errors.add("schema mismatch: expected '" + EXPECTED_SCHEMA + "', got '" + r.getSchema() + "'");
        }
        if (r.getSymbol() == null || r.getSymbol().isBlank()) {
            errors.add("symbol missing");
        }
        if (r.getAs_of() == null) {
            errors.add("as_of missing");
        }
        if (r.getRegime() == null) {
            errors.add("regime missing or invalid enum (see RegimeClass)");
        }
        if (r.getBias() == null) {
            errors.add("bias missing or invalid enum (see Bias)");
        }
        if (r.getConviction() == null) {
            errors.add("conviction missing or invalid enum (see Conviction)");
        }
        if (r.getValid_until() == null) {
            errors.add("valid_until missing");
        }
        if (r.getAlignment() == null) {
            errors.add("alignment missing");
        }
        if (r.getTrigger_to_watch() == null || r.getTrigger_to_watch().isBlank()) {
            errors.add("trigger_to_watch missing or blank");
        }
        if (r.getDefining_levels() == null) {
            errors.add("defining_levels missing");
        } else if (r.getDefining_levels().getInvalidation() == null) {
            errors.add("defining_levels.invalidation missing — required (kills the regime)");
        }
        // Schema rule: bias=neutral only with regime=squeeze_coiled.
        if (r.getBias() == Bias.NEUTRAL && r.getRegime() != null
                && r.getRegime() != RegimeClass.SQUEEZE_COILED) {
            errors.add("bias=neutral only allowed with regime=squeeze_coiled, got regime="
                    + r.getRegime());
        }
        // source_run, when present, must be nightly or intraday (warning, not hard fail —
        // the schema says "nightly | intraday" but the owner-stated required-fields list
        // doesn't include it; we accept missing but reject malformed).
        if (r.getSource_run() != null
                && !"nightly".equals(r.getSource_run())
                && !"intraday".equals(r.getSource_run())) {
            errors.add("source_run must be 'nightly' or 'intraday', got '" + r.getSource_run() + "'");
        }
        return errors;
    }

    /**
     * True if {@code valid_until} is in the past relative to {@code now}. Reader uses this to
     * drop stale records.
     */
    public boolean isExpired(RegimeRecord r, OffsetDateTime now) {
        if (r == null || r.getValid_until() == null) return true;
        return r.getValid_until().isBefore(now);
    }

    /** Validator result — sum type: either {@link Valid} or {@link Invalid}. */
    public sealed interface Result permits Valid, Invalid {
        String memoryId();
        boolean isValid();
    }

    public record Valid(String memoryId, RegimeRecord record) implements Result {
        @Override public boolean isValid() { return true; }
    }

    public record Invalid(String memoryId, List<String> errors) implements Result {
        @Override public boolean isValid() { return false; }
    }
}
