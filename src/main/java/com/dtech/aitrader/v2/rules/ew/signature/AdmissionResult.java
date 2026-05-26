package com.dtech.aitrader.v2.rules.ew.signature;

import java.util.Map;

/**
 * Result of one {@link EwSignatureRule#evaluate} call. Returned to the Pass-5 orchestrator and
 * surfaced in CONFIRMATION firing payloads for audit.
 *
 * @param state              {@link AdmissionState#ADMITTED}, {@link AdmissionState#INVALIDATED},
 *                           or {@link AdmissionState#PENDING}.
 * @param matchedLegsCount   how many of the signature's leading positions matched observed legs.
 * @param contradictingLeg   leg label that caused INVALIDATED (e.g. {@code "B"} when B was
 *                           observed FIVE but the zigzag signature required THREE). Null on
 *                           ADMITTED / PENDING.
 * @param reasoning          human-readable explanation surfaced to the level-map UI.
 * @param evidence           the raw discriminator signals that drove the decision.
 */
public record AdmissionResult(
        AdmissionState state,
        int matchedLegsCount,
        String contradictingLeg,
        String reasoning,
        Map<String, Object> evidence) {

    public static AdmissionResult admitted(int matched, String reasoning, Map<String, Object> ev) {
        return new AdmissionResult(AdmissionState.ADMITTED, matched, null, reasoning, ev);
    }

    public static AdmissionResult invalidated(String leg, String reasoning, Map<String, Object> ev) {
        return new AdmissionResult(AdmissionState.INVALIDATED, 0, leg, reasoning, ev);
    }

    public static AdmissionResult pending(int matched, String reasoning, Map<String, Object> ev) {
        return new AdmissionResult(AdmissionState.PENDING, matched, null, reasoning, ev);
    }
}
