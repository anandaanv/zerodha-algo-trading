package com.dtech.kitecon.elliott.choch;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.elliott.ImpulseLabeler;
import com.dtech.kitecon.elliott.ImpulseLabelStrategy;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChochStateImpulseLabeler implements ImpulseLabelStrategy {

    private double w2MinRetrace = 50.0;
    private double w2MaxRetrace = 99.0;
    private double w2HighConfidenceThreshold = 61.8;
    private double w3MinRatio = 1.61;
    private double w4MinRetrace = 23.6;
    private double w4MaxRetrace = 50.0;
    private double w5MinRatio = 0.618;

    @Override
    public String name() {
        return "choch-state";
    }

    @Override
    public List<ImpulseLabeler.LabeledPivot> label(
            List<ZigZagPoint> pivots,
            List<Bar> bars,
            Map<Instant, Integer> tsToIdx,
            List<MarketStructurePoint> structurePoints) {

        // Step A: Initialize results with "no_impulse" for all pivots
        List<ImpulseLabeler.LabeledPivot> results = new ArrayList<>();
        for (ZigZagPoint p : pivots) {
            results.add(new ImpulseLabeler.LabeledPivot(p, "no_impulse", 0, ""));
        }

        if (structurePoints.isEmpty()) {
            return results;
        }

        // Step B: Extract ChoCH events from structurePoints
        List<ChochEvent> chochEvents = extractChochEvents(pivots, structurePoints);
        log.debug("[ChochLabeler] Extracted {} ChoCH events", chochEvents.size());

        if (chochEvents.isEmpty()) {
            return results;
        }

        // Step C: Walk the event stream with state machine
        ChochLabelerState state = new ChochLabelerState();
        int wave3Count = 0, wave5Count = 0;

        for (int eventIdx = 0; eventIdx < chochEvents.size(); eventIdx++) {
            ChochEvent e = chochEvents.get(eventIdx);
            int ePivotIdx = e.barIndex();

            log.debug("[ChochLabeler] Event {}: {} @ pivot {}, price {}",
                eventIdx, e.direction(), ePivotIdx, e.price());

            // Update rolling slots
            if (e.direction() == Direction.BULLISH) {
                state.setLastBullishChoch(e);
            } else {
                state.setLastBearishChoch(e);
            }

            // If no pending impulse, start a new one
            if (state.getPending() == null) {
                ChochImpulseCount newCount = ChochImpulseCount.builder()
                    .chainId(UUID.randomUUID())
                    .origin(e)
                    .originOriginalChochIdx(e.originalChochPivotIdx())
                    .direction(e.direction())
                    .status(ChochImpulseCount.Status.PENDING_W2)
                    .build();
                state.setPending(newCount);
                log.debug("[ChochLabeler] Created new impulse count: {}", newCount.getChainId());
                continue;
            }

            // Dispatch on pending status
            switch (state.getPending().getStatus()) {
                case PENDING_W2:
                    processPendingW2(state, e, ePivotIdx, pivots, results);
                    break;
                case PENDING_W3:
                    processPendingW3(state, e, ePivotIdx, pivots, results);
                    break;
                case W3_CONFIRMED:
                case PENDING_W4:
                    processPendingW4(state, e, ePivotIdx, pivots, results);
                    break;
                case PENDING_W5:
                    processPendingW5(state, e, ePivotIdx, pivots, results);
                    break;
                default:
                    break;
            }
        }

        // Count completed wave3 and wave5 labels
        wave3Count = (int) results.stream()
            .filter(lp -> lp.label().equals("wave3_start"))
            .count();
        wave5Count = (int) results.stream()
            .filter(lp -> lp.label().equals("wave5_start"))
            .count();

        int invalidated = (int) state.getCompleted().stream()
            .filter(c -> c.getStatus() == ChochImpulseCount.Status.INVALIDATED)
            .count();

        log.info("[ChochLabeler] Processed {} reversal-anchored ChoCHs -> wave3={} wave5={} invalidated={} orphaned={}",
            chochEvents.size(), wave3Count, wave5Count, invalidated, state.getOrphanedChochs().size());

        return results;
    }

    private void processPendingW2(ChochLabelerState state, ChochEvent e, int ePivotIdx,
                                   List<ZigZagPoint> pivots, List<ImpulseLabeler.LabeledPivot> results) {
        ChochImpulseCount pending = state.getPending();

        // Expect opposite direction for wave 2 end
        if (e.direction() == pending.getDirection()) {
            state.getOrphanedChochs().add(e);
            log.debug("[ChochLabeler-W2] Same direction ChoCH (orphaned)");
            return;
        }

        // Find wave 1 extreme between origin ChoCH pivot and current event ChoCH pivot
        // The origin ChoCH pivot is where W1 peaks, current ChoCH pivot is where W2 ends
        int originOriginalChochIdx = pending.getOriginOriginalChochIdx();
        int currentOriginalChochIdx = e.originalChochPivotIdx();
        int w1ExtremePivotIdx = findExtremePivotIndex(pivots, originOriginalChochIdx, currentOriginalChochIdx - 1, pending.getDirection());

        if (w1ExtremePivotIdx < 0) {
            // Invalidate
            pending.setStatus(ChochImpulseCount.Status.INVALIDATED);
            state.getCompleted().add(pending);
            state.setPending(null);

            // e becomes new origin
            ChochImpulseCount newCount = ChochImpulseCount.builder()
                .chainId(UUID.randomUUID())
                .origin(e)
                .originOriginalChochIdx(e.originalChochPivotIdx())
                .direction(e.direction())
                .status(ChochImpulseCount.Status.PENDING_W2)
                .build();
            state.setPending(newCount);
            log.debug("[ChochLabeler-W2] No valid w1 extreme, invalidated");
            return;
        }

        double w1EndPrice = pivots.get(w1ExtremePivotIdx).getValue();
        double w1Size = Math.abs(w1EndPrice - pending.getOrigin().price());

        if (w1Size == 0) {
            pending.setStatus(ChochImpulseCount.Status.INVALIDATED);
            state.getCompleted().add(pending);
            state.setPending(null);

            ChochImpulseCount newCount = ChochImpulseCount.builder()
                .chainId(UUID.randomUUID())
                .origin(e)
                .direction(e.direction())
                .status(ChochImpulseCount.Status.PENDING_W2)
                .build();
            state.setPending(newCount);
            log.debug("[ChochLabeler-W2] w1Size is zero, invalidated");
            return;
        }

        double retracePct = Math.abs(e.originalChochPrice() - w1EndPrice) / w1Size * 100.0;

        if (retracePct > 100.0) {
            // Invalidate
            pending.setStatus(ChochImpulseCount.Status.INVALIDATED);
            state.getCompleted().add(pending);

            ChochImpulseCount newCount = ChochImpulseCount.builder()
                .chainId(UUID.randomUUID())
                .origin(e)
                .direction(e.direction())
                .status(ChochImpulseCount.Status.PENDING_W2)
                .build();
            state.setPending(newCount);
            log.debug("[ChochLabeler-W2] Retrace > 100%, invalidated and restarted");
            return;
        }

        if (retracePct < w2MinRetrace || retracePct > w2MaxRetrace) {
            if (retracePct < w2MinRetrace) {
                state.getOrphanedChochs().add(e);
                log.debug("[ChochLabeler-W2] Retrace {:.1f}% < min {}, orphaned", retracePct, w2MinRetrace);
            } else {
                // retracePct > w2MaxRetrace (and <= 100), invalidate
                pending.setStatus(ChochImpulseCount.Status.INVALIDATED);
                state.getCompleted().add(pending);

                ChochImpulseCount newCount = ChochImpulseCount.builder()
                    .chainId(UUID.randomUUID())
                    .origin(e)
                    .direction(e.direction())
                    .status(ChochImpulseCount.Status.PENDING_W2)
                    .build();
                state.setPending(newCount);
                log.debug("[ChochLabeler-W2] Retrace {:.1f}% > max {}, invalidated", retracePct, w2MaxRetrace);
            }
            return;
        }

        // Valid retrace: set w2 fields
        pending.setW1EndPivotIdx(w1ExtremePivotIdx);
        pending.setW1EndPrice(w1EndPrice);
        pending.setW2End(e);
        pending.setW2EndPivotIdx(ePivotIdx);
        pending.setW2RetracePct(retracePct);
        pending.setW2Confidence(retracePct >= w2HighConfidenceThreshold ?
            ChochLabelConfidence.HIGH : ChochLabelConfidence.LOW);
        pending.setStatus(ChochImpulseCount.Status.PENDING_W3);

        log.debug("[ChochLabeler-W2] Valid retrace {:.1f}%, advancing to PENDING_W3, chainId={}",
            retracePct, pending.getChainId());
    }

    private void processPendingW3(ChochLabelerState state, ChochEvent e, int ePivotIdx,
                                   List<ZigZagPoint> pivots, List<ImpulseLabeler.LabeledPivot> results) {
        ChochImpulseCount pending = state.getPending();

        if (e.direction() == pending.getDirection()) {
            // Same direction: find w3 extreme and measure
            int w2EndPivotIdx = pending.getW2EndPivotIdx();
            int w3ExtremePivotIdx = findExtremePivotIndex(pivots, w2EndPivotIdx + 1, e.originalChochPivotIdx(), pending.getDirection());

            if (w3ExtremePivotIdx < 0) {
                // No extreme found, stay in PENDING_W3
                return;
            }

            double w3EndPrice = pivots.get(w3ExtremePivotIdx).getValue();
            double w3Size = Math.abs(w3EndPrice - pending.getW2End().originalChochPrice());
            double w1Size = Math.abs(pending.getW1EndPrice() - pending.getOrigin().price());
            double w3Ratio = w1Size > 0 ? w3Size / w1Size : 0;

            if (w3Ratio >= w3MinRatio) {
                // Emit wave3_start label
                pending.setW3EndPivotIdx(w3ExtremePivotIdx);
                pending.setW3EndPrice(w3EndPrice);
                pending.setW3Ratio(w3Ratio);
                pending.setStatus(ChochImpulseCount.Status.PENDING_W4);

                ZigZagPoint w2EndPivot = pivots.get(pending.getW2EndPivotIdx());
                int direction = pending.getDirection() == Direction.BULLISH ? 1 : -1;
                String detail = buildDetail(pending);
                results.set(pending.getW2EndPivotIdx(),
                    new ImpulseLabeler.LabeledPivot(w2EndPivot, "wave3_start", direction, detail));

                log.info("[ChochLabeler-W3] Emitted wave3_start: ratio={:.2f}, conf={}, chainId={}",
                    w3Ratio, pending.getW2Confidence(), pending.getChainId());
            } else {
                // Keep high-watermark
                if (pending.getW3EndPivotIdx() == 0 || w3Ratio > pending.getW3Ratio()) {
                    pending.setW3EndPivotIdx(w3ExtremePivotIdx);
                    pending.setW3EndPrice(w3EndPrice);
                    pending.setW3Ratio(w3Ratio);
                }
                log.debug("[ChochLabeler-W3] Ratio {:.2f} < threshold {}, updating high-watermark", w3Ratio, w3MinRatio);
            }
        } else {
            // Opposite direction: check high-watermark
            if (pending.getW3Ratio() >= w3MinRatio) {
                // Emit label retroactively
                ZigZagPoint w2EndPivot = pivots.get(pending.getW2EndPivotIdx());
                int direction = pending.getDirection() == Direction.BULLISH ? 1 : -1;
                String detail = buildDetail(pending);
                results.set(pending.getW2EndPivotIdx(),
                    new ImpulseLabeler.LabeledPivot(w2EndPivot, "wave3_start", direction, detail));

                pending.setStatus(ChochImpulseCount.Status.PENDING_W4);
                pending.setW4End(e);
                pending.setW4EndPivotIdx(ePivotIdx);

                log.info("[ChochLabeler-W3] Emitted wave3_start retroactively: ratio={:.2f}, chainId={}",
                    pending.getW3Ratio(), pending.getChainId());
            } else {
                // Check overlap with origin
                boolean isBullish = pending.getDirection() == Direction.BULLISH;
                boolean breachesOrigin = isBullish ? e.price() < pending.getOrigin().price() :
                    e.price() > pending.getOrigin().price();

                if (breachesOrigin) {
                    // Invalidate
                    pending.setStatus(ChochImpulseCount.Status.INVALIDATED);
                    state.getCompleted().add(pending);

                    ChochImpulseCount newCount = ChochImpulseCount.builder()
                        .chainId(UUID.randomUUID())
                        .origin(e)
                        .originOriginalChochIdx(e.originalChochPivotIdx())
                        .direction(e.direction())
                        .status(ChochImpulseCount.Status.PENDING_W2)
                        .build();
                    state.setPending(newCount);
                    log.debug("[ChochLabeler-W3] Opposite ChoCH breaches origin, invalidated");
                } else {
                    state.getOrphanedChochs().add(e);
                    log.debug("[ChochLabeler-W3] Opposite ChoCH doesn't breach origin, orphaned");
                }
            }
        }
    }

    private void processPendingW4(ChochLabelerState state, ChochEvent e, int ePivotIdx,
                                   List<ZigZagPoint> pivots, List<ImpulseLabeler.LabeledPivot> results) {
        ChochImpulseCount pending = state.getPending();

        // Expect opposite direction
        if (e.direction() == pending.getDirection()) {
            state.getOrphanedChochs().add(e);
            log.debug("[ChochLabeler-W4] Same direction ChoCH (orphaned)");
            return;
        }

        // Check overlap with w1 end
        boolean isBullish = pending.getDirection() == Direction.BULLISH;
        boolean overlapsW1 = isBullish ? e.price() <= pending.getW1EndPrice() :
            e.price() >= pending.getW1EndPrice();

        if (overlapsW1) {
            // Elliott hard rule violation
            pending.setStatus(ChochImpulseCount.Status.COMPLETED);
            state.getCompleted().add(pending);
            state.setPending(null);

            ChochImpulseCount newCount = ChochImpulseCount.builder()
                .chainId(UUID.randomUUID())
                .origin(e)
                .direction(e.direction())
                .status(ChochImpulseCount.Status.PENDING_W2)
                .build();
            state.setPending(newCount);
            log.debug("[ChochLabeler-W4] Wave 4 overlaps wave 1, completed (no wave5)");
            return;
        }

        double w3Size = Math.abs(pending.getW3EndPrice() - pending.getW2End().originalChochPrice());
        double w4Retrace = w3Size > 0 ? Math.abs(e.originalChochPrice() - pending.getW3EndPrice()) / w3Size * 100.0 : 0;

        if (w4Retrace < w4MinRetrace || w4Retrace > w4MaxRetrace) {
            state.getOrphanedChochs().add(e);
            log.debug("[ChochLabeler-W4] Retrace {:.1f}% outside [{}, {}], orphaned",
                w4Retrace, w4MinRetrace, w4MaxRetrace);
            return;
        }

        // Valid w4 retrace
        pending.setW4End(e);
        pending.setW4EndPivotIdx(ePivotIdx);
        pending.setW4RetracePct(w4Retrace);
        pending.setStatus(ChochImpulseCount.Status.PENDING_W5);

        log.debug("[ChochLabeler-W4] Valid retrace {:.1f}%, advancing to PENDING_W5", w4Retrace);
    }

    private void processPendingW5(ChochLabelerState state, ChochEvent e, int ePivotIdx,
                                   List<ZigZagPoint> pivots, List<ImpulseLabeler.LabeledPivot> results) {
        ChochImpulseCount pending = state.getPending();

        if (e.direction() != pending.getDirection()) {
            // Opposite direction: wave5 didn't form
            pending.setStatus(ChochImpulseCount.Status.COMPLETED);
            state.getCompleted().add(pending);
            state.setPending(null);

            ChochImpulseCount newCount = ChochImpulseCount.builder()
                .chainId(UUID.randomUUID())
                .origin(e)
                .direction(e.direction())
                .status(ChochImpulseCount.Status.PENDING_W2)
                .build();
            state.setPending(newCount);
            log.debug("[ChochLabeler-W5] Opposite ChoCH, completed without wave5");
            return;
        }

        // Same direction: find w5 extreme
        int w4EndPivotIdx = pending.getW4EndPivotIdx();
        int w5ExtremePivotIdx = findExtremePivotIndex(pivots, w4EndPivotIdx + 1, e.originalChochPivotIdx(), pending.getDirection());

        if (w5ExtremePivotIdx < 0) {
            // No extreme found, stay in PENDING_W5
            return;
        }

        double w5EndPrice = pivots.get(w5ExtremePivotIdx).getValue();
        double w5Size = Math.abs(w5EndPrice - pending.getW4End().originalChochPrice());
        double w3Size = Math.abs(pending.getW3EndPrice() - pending.getW2End().originalChochPrice());
        double w1Size = Math.abs(pending.getW1EndPrice() - pending.getOrigin().price());
        double w5Ratio = w3Size > 0 ? w5Size / w3Size : 0;

        if (w5Ratio >= w5MinRatio) {
            // Emit wave5_start label
            pending.setW5EndPivotIdx(w5ExtremePivotIdx);
            pending.setW5EndPrice(w5EndPrice);
            pending.setW5Ratio(w5Ratio);

            ZigZagPoint w4EndPivot = pivots.get(pending.getW4EndPivotIdx());
            int direction = pending.getDirection() == Direction.BULLISH ? 1 : -1;
            String detail = buildDetail(pending);
            results.set(pending.getW4EndPivotIdx(),
                new ImpulseLabeler.LabeledPivot(w4EndPivot, "wave5_start", direction, detail));

            log.info("[ChochLabeler-W5] Emitted wave5_start: ratio={:.2f}, conf={}, chainId={}",
                w5Ratio, pending.getW2Confidence(), pending.getChainId());

            pending.setStatus(ChochImpulseCount.Status.COMPLETED);
            state.getCompleted().add(pending);
            state.setPending(null);

            // e becomes new origin
            ChochImpulseCount newCount = ChochImpulseCount.builder()
                .chainId(UUID.randomUUID())
                .origin(e)
                .direction(e.direction())
                .status(ChochImpulseCount.Status.PENDING_W2)
                .build();
            state.setPending(newCount);
        } else {
            // Update high-watermark
            if (pending.getW5EndPivotIdx() == 0 || w5Ratio > pending.getW5Ratio()) {
                pending.setW5EndPivotIdx(w5ExtremePivotIdx);
                pending.setW5EndPrice(w5EndPrice);
                pending.setW5Ratio(w5Ratio);
            }
            log.debug("[ChochLabeler-W5] Ratio {:.2f} < threshold {}, updating high-watermark", w5Ratio, w5MinRatio);
        }
    }

    private List<ChochEvent> extractChochEvents(List<ZigZagPoint> pivots, List<MarketStructurePoint> structurePoints) {
        List<ChochEvent> events = new ArrayList<>();

        for (MarketStructurePoint sp : structurePoints) {
            if (sp.getStructureLabel() == StructureLabel.CHOCH_HIGH ||
                sp.getStructureLabel() == StructureLabel.CHOCH_LOW) {

                Direction dir = sp.getStructureLabel() == StructureLabel.CHOCH_HIGH ?
                    Direction.BULLISH : Direction.BEARISH;

                // Find matching pivot by timestamp (linear scan)
                int chochPivotIdx = -1;
                for (int i = 0; i < pivots.size(); i++) {
                    if (pivots.get(i).getTimestamp().equals(sp.getTimestamp())) {
                        chochPivotIdx = i;
                        break;
                    }
                }

                // Skip if no preceding pivot (can't determine reversal extreme)
                if (chochPivotIdx <= 0) {
                    log.debug("[ChochLabeler] Skipping ChoCH event at {}: no preceding pivot", sp.getTimestamp());
                    continue;
                }

                // Use the preceding pivot as the reversal anchor
                ZigZagPoint reversalPivot = pivots.get(chochPivotIdx - 1);
                ChochEvent event = new ChochEvent(
                    chochPivotIdx - 1,  // barIndex is the reversal pivot's index (for result assignment)
                    reversalPivot.getTimestamp(),  // timestamp from reversal pivot
                    reversalPivot.getValue(),  // price from reversal pivot
                    dir,
                    reversalPivot,
                    chochPivotIdx,  // originalChochPivotIdx for state machine logic
                    sp.getPrice()  // originalChochPrice for wave measurements
                );
                events.add(event);
            }
        }

        // Sort by timestamp
        events.sort((a, b) -> a.timestamp().compareTo(b.timestamp()));

        // Dedup: if two events map to same barIndex, keep only the first by timestamp
        List<ChochEvent> dedupedEvents = new ArrayList<>();
        java.util.Set<Integer> seenBarIndices = new java.util.HashSet<>();
        for (ChochEvent e : events) {
            if (!seenBarIndices.contains(e.barIndex())) {
                dedupedEvents.add(e);
                seenBarIndices.add(e.barIndex());
            }
        }

        return dedupedEvents;
    }

    private int findExtremePivotIndex(List<ZigZagPoint> pivots, int fromIdxInclusive, int toIdxInclusive, Direction dir) {
        if (fromIdxInclusive > toIdxInclusive || fromIdxInclusive < 0 || toIdxInclusive >= pivots.size()) {
            return -1;
        }

        int extremeIdx = fromIdxInclusive;
        double extremeValue = pivots.get(fromIdxInclusive).getValue();

        for (int i = fromIdxInclusive + 1; i <= toIdxInclusive; i++) {
            double val = pivots.get(i).getValue();
            if (dir == Direction.BULLISH) {
                if (val > extremeValue) {
                    extremeValue = val;
                    extremeIdx = i;
                }
            } else {
                if (val < extremeValue) {
                    extremeValue = val;
                    extremeIdx = i;
                }
            }
        }

        return extremeIdx;
    }

    private String buildDetail(ChochImpulseCount c) {
        double w3Ratio = Double.isNaN(c.getW3Ratio()) ? 0.0 : c.getW3Ratio();
        double w5Ratio = Double.isNaN(c.getW5Ratio()) ? 0.0 : c.getW5Ratio();
        double w2RetracePct = Double.isNaN(c.getW2RetracePct()) ? 0.0 : c.getW2RetracePct();

        return String.format(
            "W1: %.2f→%.2f (%.2f%%) | W2ret: %.1f%% [%s] | W3: %.2fx | W5: %.2fx",
            c.getOrigin().price(),
            c.getW1EndPrice(),
            Math.abs(c.getW1EndPrice() - c.getOrigin().price()),
            w2RetracePct,
            c.getW2Confidence() != null ? c.getW2Confidence() : "N/A",
            w3Ratio,
            w5Ratio
        );
    }
}
