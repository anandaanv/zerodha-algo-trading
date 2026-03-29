package com.dtech.kitecon.scan.service;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.analysis.AnalysisProcessService;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.scan.entity.*;
import com.dtech.kitecon.scan.repository.*;
import com.dtech.kitecon.screener.elliott.service.*;
import com.dtech.kitecon.service.copilot.*;
import com.dtech.kitecon.service.copilot.dto.*;
import com.dtech.ta.elliott.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnDemandScanService {

    private final ScanRateLimitService rateLimitService;
    private final OnDemandScanRepository scanRepository;
    private final OnDemandScanLayoutRepository layoutRepository;
    private final ScanGroupMemberRepository groupMemberRepository;
    private final ZigZagService zigzagService;
    private final MarketStructureService marketStructureService;
    private final AdvancedElliottService advancedElliottService;
    private final AnalysisProcessService analysisProcessService;
    private final CopilotOrchestratorService orchestratorService;
    private final CopilotAIService aiService;
    private final AIResponseParser responseParser;
    private final InstrumentRepository instrumentRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public OnDemandScan initiateScan(Long userId, String symbol, String primaryTimeframe) {
        rateLimitService.checkRateLimit(userId);

        List<String> allTfs = expandTimeframes(primaryTimeframe);
        OnDemandScan scan = OnDemandScan.builder()
                .userId(userId)
                .symbol(symbol.toUpperCase().trim())
                .primaryTimeframe(primaryTimeframe)
                .allTimeframes(String.join(",", allTfs))
                .status(ScanStatus.PENDING)
                .build();
        scan = scanRepository.save(scan);
        runScanAsync(scan.getId(), userId, symbol.toUpperCase().trim(), primaryTimeframe);
        return scan;
    }

    @Async("scanExecutor")
    public void runScanAsync(Long scanId, Long userId, String symbol, String primaryTf) {
        OnDemandScan scan = scanRepository.findById(scanId).orElse(null);
        if (scan == null) return;

        scan.setStatus(ScanStatus.RUNNING);
        scanRepository.save(scan);

        try {
            var instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
            if (instrument == null) throw new IllegalArgumentException("Instrument not found: " + symbol);

            List<String> tfs = expandTimeframes(primaryTf);

            Map<String, List<ZigZagPoint>> pivotsByTf = new LinkedHashMap<>();
            Map<String, MarketStructureData> structureByTf = new LinkedHashMap<>();
            Map<String, BarSeries> seriesByTf = new LinkedHashMap<>();
            List<String> orderedTfs = new ArrayList<>();
            StringBuilder msdSummary = new StringBuilder();

            for (String tf : tfs) {
                Interval interval = ElliottScreenerService.mapTimeframeToInterval(tf);
                if (interval == null) { log.warn("[OnDemandScan] Unknown tf {}", tf); continue; }
                try {
                    List<ZigZagPoint> pivots = zigzagService.getOrComputePivots(symbol, instrument, interval);
                    BarSeries series = zigzagService.getBarSeries(symbol, instrument, interval);
                    List<ZigZagPoint> pivotsWithLtp = zigzagService.withLtpPivot(pivots, series);
                    MarketStructureData msd = marketStructureService.analyse(pivotsWithLtp, tf);
                    msdSummary.append(msd.toPromptSummary()).append("\n");
                    pivotsByTf.put(tf, pivotsWithLtp);
                    structureByTf.put(tf, msd);
                    seriesByTf.put(tf, series);
                    orderedTfs.add(tf);
                } catch (Exception e) {
                    log.warn("[OnDemandScan] TF {} failed for {}: {}", tf, symbol, e.getMessage());
                }
            }

            if (orderedTfs.isEmpty()) throw new IllegalStateException("No valid timeframes computed");

            String effectivePrimaryTf = orderedTfs.contains(primaryTf) ? primaryTf : orderedTfs.get(orderedTfs.size() - 1);

            AdvancedElliottAnalysisResult advResult = advancedElliottService.analyze(
                    seriesByTf, pivotsByTf, structureByTf, orderedTfs, symbol, effectivePrimaryTf);

            BarSeries primarySeries = seriesByTf.get(effectivePrimaryTf);
            double currentPrice = (primarySeries != null && primarySeries.getBarCount() > 0)
                    ? primarySeries.getLastBar().getClosePrice().doubleValue() : 0.0;

            var processed = analysisProcessService.process(advResult.getWaveAnalysis(), symbol, currentPrice, effectivePrimaryTf);

            String data = "=== SYMBOL: " + symbol + " ===\n" + processed.getHumanReadable() + "\n" + msdSummary;
            String instructions = orchestratorService.buildScenarioEvaluatorInstructions();
            String rawResponse = aiService.call(userId, instructions, data);

            AIResponse aiResponse = responseParser.parse(rawResponse);
            boolean hasTradeSetup = (aiResponse instanceof FindingResponse fr) && "ENTRY_READY".equals(fr.getCurrentStage());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("symbol", symbol);
            result.put("primaryTimeframe", effectivePrimaryTf);
            result.put("allTimeframes", String.join(",", orderedTfs));
            result.put("humanReadable", processed.getHumanReadable());
            result.put("msdSummary", msdSummary.toString());
            result.put("aiResponse", rawResponse);
            result.put("hasTradeSetup", hasTradeSetup);
            if (hasTradeSetup) {
                result.put("aiParsed", aiResponse);
            }

            scan.setResultJson(objectMapper.writeValueAsString(result));
            scan.setStatus(ScanStatus.COMPLETED);
            scan.setCompletedAt(Instant.now());
            scan.setAllTimeframes(String.join(",", orderedTfs));
            scan.setPrimaryTimeframe(effectivePrimaryTf);
            scanRepository.save(scan);

            // Generate chart layouts
            generateLayouts(scan, advResult.getWaveAnalysis(), seriesByTf, orderedTfs, effectivePrimaryTf);

        } catch (Exception e) {
            log.error("[OnDemandScan] Scan {} failed: {}", scanId, e.getMessage(), e);
            scan.setStatus(ScanStatus.FAILED);
            scan.setErrorMessage(e.getMessage());
            scan.setCompletedAt(Instant.now());
            scanRepository.save(scan);
        }
    }

    private void generateLayouts(OnDemandScan scan, ElliottWaveAnalysis waveAnalysis,
                                  Map<String, BarSeries> seriesByTf,
                                  List<String> orderedTfs, String primaryTf) {
        layoutRepository.deleteByScanId(scan.getId());

        for (int idx = 0; idx < orderedTfs.size(); idx++) {
            String tf = orderedTfs.get(idx);
            try {
                BarSeries series = seriesByTf.get(tf);
                boolean isPrimaryTf = tf.equals(primaryTf);

                Map<String, Object> overlays = new LinkedHashMap<>();
                // trendlines from wave analysis
                List<Map<String, Object>> trendlines = new ArrayList<>();
                if (waveAnalysis != null && waveAnalysis.getVirginTrendlines() != null) {
                    for (var vt : waveAnalysis.getVirginTrendlines()) {
                        try {
                            Map<String, Object> shape = new LinkedHashMap<>();
                            Map<String, Object> p1 = new LinkedHashMap<>();
                            p1.put("time", vt.getAnchor1().getTimestamp().getEpochSecond());
                            p1.put("price", vt.getAnchor1().getPrice());
                            Map<String, Object> p2 = new LinkedHashMap<>();
                            p2.put("time", vt.getAnchor2().getTimestamp().getEpochSecond());
                            p2.put("price", vt.getAnchor2().getPrice());
                            shape.put("points", List.of(p1, p2));
                            Map<String, Object> props = new LinkedHashMap<>();
                            props.put("color", "#90caf9");
                            props.put("width", 1);
                            props.put("style", "dashed");
                            shape.put("props", props);
                            trendlines.add(shape);
                        } catch (Exception e) {
                            log.warn("Failed trendline: {}", e.getMessage());
                        }
                    }
                }
                overlays.put("trendline", trendlines);
                overlays.put("hline", new ArrayList<>());
                overlays.put("elliott-impulse", new ArrayList<>());

                OnDemandScanLayout layout = OnDemandScanLayout.builder()
                        .scanId(scan.getId())
                        .timeframe(tf)
                        .tabOrder(idx)
                        .overlaysJson(objectMapper.writeValueAsString(overlays))
                        .build();
                layoutRepository.save(layout);
            } catch (Exception e) {
                log.error("Failed layout for scan {} tf {}: {}", scan.getId(), tf, e.getMessage(), e);
            }
        }
    }

    public Optional<OnDemandScan> getScan(Long scanId) {
        return scanRepository.findById(scanId);
    }

    public List<OnDemandScan> getVisibleScans(Long userId) {
        Set<Long> visibleUserIds = new LinkedHashSet<>();
        visibleUserIds.add(userId);
        // Add all users from groups this user belongs to
        groupMemberRepository.findByUserId(userId).forEach(m -> {
            groupMemberRepository.findByGroupId(m.getGroupId())
                    .forEach(gm -> visibleUserIds.add(gm.getUserId()));
        });
        return scanRepository.findByUserIdInOrderByRequestedAtDesc(new ArrayList<>(visibleUserIds));
    }

    public List<OnDemandScanLayout> getScanLayouts(Long scanId) {
        return layoutRepository.findByScanIdOrderByTabOrder(scanId);
    }

    public List<String> expandTimeframes(String primaryTf) {
        if (primaryTf == null) return List.of();
        return switch (primaryTf.toLowerCase().trim()) {
            case "1w", "weekly", "week" -> List.of("1w");
            case "1d", "daily", "day" -> List.of("1w", "1d");
            case "4h", "4hour", "240" -> List.of("1w", "1d", "4h");
            case "1h", "60min", "60minute" -> List.of("1d", "4h", "1h");
            case "30min", "30m", "30minute" -> List.of("1d", "4h", "1h", "30min");
            case "15min", "15m", "15minute" -> List.of("1d", "1h", "15min");
            default -> List.of(primaryTf);
        };
    }
}
