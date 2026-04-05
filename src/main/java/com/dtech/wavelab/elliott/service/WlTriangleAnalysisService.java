package com.dtech.wavelab.elliott.service;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.wavelab.elliott.dto.TriangleAnalyzeRequest;
import com.dtech.wavelab.elliott.dto.TriangleEvaluatorOutput;
import com.dtech.wavelab.elliott.dto.TriangleModelOutput;
import com.dtech.wavelab.elliott.dto.TriangleRunResponse;
import com.dtech.wavelab.elliott.entity.WleTriangleRun;
import com.dtech.wavelab.elliott.repo.WleTriangleRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class WlTriangleAnalysisService {

    private static final String PROMPT_VERSION = "v1";

    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;
    private final WleTriangleRunRepository runRepository;
    private final TrianglePromptTemplateService promptService;
    private final ObjectMapper objectMapper;

    @Value("${wavelab.elliott.triangle.model.deepseek.base-url:http://localhost:9090/v1/deepseek}")
    private String deepseekBaseUrl;

    @Value("${wavelab.elliott.triangle.model.deepseek.model:deepseek}")
    private String deepseekModel;

    @Value("${wavelab.elliott.triangle.model.qwen.base-url:http://localhost:9090/v1/qwen}")
    private String qwenBaseUrl;

    @Value("${wavelab.elliott.triangle.model.qwen.model:qwen}")
    private String qwenModel;

    @Value("${wavelab.elliott.triangle.model.phi.base-url:http://localhost:9090/v1/phi}")
    private String phiBaseUrl;

    @Value("${wavelab.elliott.triangle.model.phi.model:phi}")
    private String phiModel;

    public TriangleRunResponse analyze(Long userId, TriangleAnalyzeRequest request) {
        if (request == null || isBlank(request.getSymbol()) || isBlank(request.getTimeframe())) {
            throw new IllegalArgumentException("symbol and timeframe are required");
        }

        String symbol = request.getSymbol().trim().toUpperCase(Locale.ROOT);
        String timeframe = request.getTimeframe().trim().toLowerCase(Locale.ROOT);
        int candleLimit = normalizeCandleLimit(request.getCandleLimit());

        String proposerA = defaultModelKey(request.getProposerA(), "deepseek");
        String proposerB = defaultModelKey(request.getProposerB(), "qwen");
        String evaluator = defaultModelKey(request.getEvaluator(), "phi");

        ensureKnownModel(proposerA);
        ensureKnownModel(proposerB);
        ensureKnownModel(evaluator);

        WleTriangleRun run = WleTriangleRun.builder()
                .userId(userId)
                .symbol(symbol)
                .timeframe(timeframe)
                .candleCount(candleLimit)
                .status("RUNNING")
                .proposerA(proposerA)
                .proposerB(proposerB)
                .evaluator(evaluator)
                .build();
        run = runRepository.save(run);

        try {
            Interval interval = Interval.fromUiKey(timeframe);
            Instrument instrument = resolveInstrument(symbol);

            BarSeries fullSeries = zigZagService.getBarSeries(symbol, instrument, interval);
            if (fullSeries == null || fullSeries.isEmpty()) {
                throw new IllegalArgumentException("No candle data found for " + symbol + " " + timeframe);
            }

            BarSeries series = trimSeries(fullSeries, candleLimit);

            ZigZagParams params = zigZagService.resolveParams(symbol, interval);
            List<ZigZagPoint> pivots = zigZagService.detect(series, params);

            Map<String, Object> inputSummary = buildInputSummary(symbol, timeframe, series, pivots);
            String inputSummaryJson = objectMapper.writeValueAsString(inputSummary);
            run.setInputSummaryJson(inputSummaryJson);

            String proposerSystem = promptService.load("proposer_system", PROMPT_VERSION);
            String proposerUserTemplate = promptService.load("proposer_user", PROMPT_VERSION);
            String proposerUser = promptService.render(proposerUserTemplate, Map.of(
                    "symbol", symbol,
                    "timeframe", timeframe,
                    "candle_count", String.valueOf(series.getBarCount()),
                    "input_summary_json", inputSummaryJson
            ));

            CompletableFuture<String> aFuture = CompletableFuture.supplyAsync(
                    () -> callModel(proposerA, proposerSystem, proposerUser));
            CompletableFuture<String> bFuture = CompletableFuture.supplyAsync(
                    () -> callModel(proposerB, proposerSystem, proposerUser));

            String proposerARaw = aFuture.join();
            String proposerBRaw = bFuture.join();

            run.setProposerAOutputJson(proposerARaw);
            run.setProposerBOutputJson(proposerBRaw);

            TriangleModelOutput aParsed = parseProposerOutput(proposerARaw);
            TriangleModelOutput bParsed = parseProposerOutput(proposerBRaw);

            String evaluatorSystem = promptService.load("evaluator_system", PROMPT_VERSION);
            String evaluatorUserTemplate = promptService.load("evaluator_user", PROMPT_VERSION);
            String evaluatorUser = promptService.render(evaluatorUserTemplate, Map.of(
                    "input_summary_json", inputSummaryJson,
                    "model_a_output_json", objectMapper.writeValueAsString(aParsed),
                    "model_b_output_json", objectMapper.writeValueAsString(bParsed)
            ));

            String evaluatorRaw = callModel(evaluator, evaluatorSystem, evaluatorUser);
            run.setEvaluatorOutputJson(evaluatorRaw);

            TriangleEvaluatorOutput finalOutput = parseEvaluatorOutput(evaluatorRaw);
            run.setFinalTriangleType(safeUpper(finalOutput.getFinalTriangleType(), "NONE"));
            run.setFinalStatus(safeUpper(finalOutput.getFinalStatus(), "NOT_PRESENT"));
            run.setFinalConfidence(normalizeConfidence(finalOutput.getFinalConfidence()));
            run.setSelectedSource(safeUpper(finalOutput.getSelectedSource(), "SYNTHESIZED"));
            run.setFinalReason(nonBlank(finalOutput.getWhySelected(), "No evaluator reasoning provided"));
            run.setStatus("COMPLETED");

            run = runRepository.save(run);
            return TriangleRunResponse.from(run);
        } catch (IllegalArgumentException e) {
            run.setStatus("FAILED");
            run.setErrorMessage(e.getMessage());
            runRepository.save(run);
            throw e;
        } catch (Exception e) {
            log.error("Triangle analysis failed for {} {}", symbol, timeframe, e);
            run.setStatus("FAILED");
            run.setErrorMessage(e.getMessage());
            runRepository.save(run);
            throw new RuntimeException("Triangle analysis failed: " + e.getMessage(), e);
        }
    }

    public TriangleRunResponse getRun(Long userId, Long id) {
        WleTriangleRun run = runRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Triangle run not found: " + id));
        return TriangleRunResponse.from(run);
    }

    private Instrument resolveInstrument(String symbol) {
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"BSE"});
        }
        if (instrument == null) {
            throw new IllegalArgumentException("Instrument not found: " + symbol);
        }
        return instrument;
    }

    private int normalizeCandleLimit(Integer requested) {
        int n = requested == null ? 1000 : requested;
        if (n < 100) n = 100;
        return Math.min(n, 1000);
    }

    private String defaultModelKey(String raw, String fallback) {
        return isBlank(raw) ? fallback : raw.trim().toLowerCase(Locale.ROOT);
    }

    private void ensureKnownModel(String key) {
        resolveEndpoint(key);
    }

    private String callModel(String modelKey, String systemPrompt, String userPrompt) {
        ModelEndpoint endpoint = resolveEndpoint(modelKey);

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey("local")
                .baseUrl(endpoint.baseUrl())
                .maxRetries(1)
                .build();

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(endpoint.model())
                .addSystemMessage(systemPrompt)
                .addUserMessage(userPrompt)
                .build();

        var completion = client.chat().completions().create(params);
        return completion.choices().stream()
                .findFirst()
                .map(c -> c.message().content().orElse(""))
                .orElse("");
    }

    private TriangleModelOutput parseProposerOutput(String raw) {
        try {
            String json = extractJsonObject(raw);
            TriangleModelOutput out = objectMapper.readValue(json, TriangleModelOutput.class);
            if (out.getTriangleType() == null) out.setTriangleType("NONE");
            if (out.getStatus() == null) out.setStatus("NOT_PRESENT");
            out.setTriangleType(safeUpper(out.getTriangleType(), "NONE"));
            out.setStatus(safeUpper(out.getStatus(), "NOT_PRESENT"));
            out.setConfidence(normalizeConfidence(out.getConfidence()));
            return out;
        } catch (Exception e) {
            TriangleModelOutput fallback = new TriangleModelOutput();
            fallback.setTriangleType("NONE");
            fallback.setStatus("NOT_PRESENT");
            fallback.setConfidence(0.0);
            fallback.setInvalidationReason("Failed to parse proposer output");
            return fallback;
        }
    }

    private TriangleEvaluatorOutput parseEvaluatorOutput(String raw) {
        try {
            String json = extractJsonObject(raw);
            TriangleEvaluatorOutput out = objectMapper.readValue(json, TriangleEvaluatorOutput.class);
            if (out.getFinalTriangleType() == null) out.setFinalTriangleType("NONE");
            if (out.getFinalStatus() == null) out.setFinalStatus("NOT_PRESENT");
            out.setFinalTriangleType(safeUpper(out.getFinalTriangleType(), "NONE"));
            out.setFinalStatus(safeUpper(out.getFinalStatus(), "NOT_PRESENT"));
            out.setFinalConfidence(normalizeConfidence(out.getFinalConfidence()));
            out.setSelectedSource(safeUpper(out.getSelectedSource(), "SYNTHESIZED"));
            return out;
        } catch (Exception e) {
            TriangleEvaluatorOutput fallback = new TriangleEvaluatorOutput();
            fallback.setFinalTriangleType("NONE");
            fallback.setFinalStatus("NOT_PRESENT");
            fallback.setFinalConfidence(0.0);
            fallback.setSelectedSource("SYNTHESIZED");
            fallback.setWhySelected("Failed to parse evaluator output");
            return fallback;
        }
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Model returned empty response");
        }

        String candidate = raw.trim();
        if (candidate.startsWith("```") && candidate.endsWith("```")) {
            int firstNewline = candidate.indexOf('\n');
            if (firstNewline > 0) {
                candidate = candidate.substring(firstNewline + 1, candidate.length() - 3).trim();
            }
        }

        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object found in model response");
        }
        return candidate.substring(start, end + 1);
    }

    private Map<String, Object> buildInputSummary(String symbol,
                                                  String timeframe,
                                                  BarSeries series,
                                                  List<ZigZagPoint> pivots) {
        int end = series.getEndIndex();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("symbol", symbol);
        summary.put("timeframe", timeframe);
        summary.put("bar_count", series.getBarCount());

        List<Map<String, Object>> candles = new ArrayList<>();
        for (int i = 0; i <= end; i++) {
            Bar bar = series.getBar(i);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("index", i);
            c.put("ts", bar.getEndTime().getEpochSecond());
            c.put("o", bar.getOpenPrice().doubleValue());
            c.put("h", bar.getHighPrice().doubleValue());
            c.put("l", bar.getLowPrice().doubleValue());
            c.put("c", bar.getClosePrice().doubleValue());
            c.put("v", bar.getVolume().doubleValue());
            candles.add(c);
        }
        summary.put("candles", candles);

        List<Map<String, Object>> pivotMaps = new ArrayList<>();
        for (ZigZagPoint p : pivots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", p.getType().name());
            m.put("bar_index", p.getBarIndex());
            m.put("ts", p.getTimestamp() != null ? p.getTimestamp().getEpochSecond() : p.getSequence());
            m.put("price", p.getValue());
            m.put("retracement_pct", p.getRetracementPct());
            m.put("extension_pct", p.getExtensionPct());
            pivotMaps.add(m);
        }
        summary.put("zigzag_pivots", pivotMaps);

        summary.put("indicator_summary", buildIndicators(series));
        return summary;
    }

    private Map<String, Object> buildIndicators(BarSeries series) {
        int idx = series.getEndIndex();
        ClosePriceIndicator close = new ClosePriceIndicator(series);

        EMAIndicator ema20 = new EMAIndicator(close, 20);
        EMAIndicator ema50 = new EMAIndicator(close, 50);
        EMAIndicator ema200 = new EMAIndicator(close, 200);

        RSIIndicator rsi14 = new RSIIndicator(close, 14);
        MACDIndicator macd = new MACDIndicator(close, 12, 26);
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);

        SMAIndicator sma20 = new SMAIndicator(close, 20);
        StandardDeviationIndicator std20 = new StandardDeviationIndicator(close, 20);
        BollingerBandsMiddleIndicator bbMid = new BollingerBandsMiddleIndicator(sma20);
        BollingerBandsUpperIndicator bbUp = new BollingerBandsUpperIndicator(bbMid, std20, series.numFactory().numOf(2));
        BollingerBandsLowerIndicator bbLow = new BollingerBandsLowerIndicator(bbMid, std20, series.numFactory().numOf(2));

        VolumeIndicator volume = new VolumeIndicator(series);
        EMAIndicator volumeEma20 = new EMAIndicator(volume, 20);

        double closePx = close.getValue(idx).doubleValue();
        double macdValue = macd.getValue(idx).doubleValue();
        double signalValue = macdSignal.getValue(idx).doubleValue();
        double histogram = macdValue - signalValue;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("close", closePx);
        out.put("ema20", ema20.getValue(idx).doubleValue());
        out.put("ema50", ema50.getValue(idx).doubleValue());
        out.put("ema200", ema200.getValue(idx).doubleValue());
        out.put("rsi14", rsi14.getValue(idx).doubleValue());
        out.put("macd", macdValue);
        out.put("macd_signal", signalValue);
        out.put("macd_histogram", histogram);
        out.put("bb_upper", bbUp.getValue(idx).doubleValue());
        out.put("bb_middle", bbMid.getValue(idx).doubleValue());
        out.put("bb_lower", bbLow.getValue(idx).doubleValue());
        out.put("bb_width_pct", Math.max(0.0,
                (bbUp.getValue(idx).doubleValue() - bbLow.getValue(idx).doubleValue()) / Math.max(closePx, 1e-9) * 100.0));

        double vol = volume.getValue(idx).doubleValue();
        double volAvg = volumeEma20.getValue(idx).doubleValue();
        out.put("volume", vol);
        out.put("volume_ema20", volAvg);
        out.put("volume_ratio", volAvg > 0 ? vol / volAvg : 0.0);

        return out;
    }

    private BarSeries trimSeries(BarSeries source, int maxBars) {
        int total = source.getBarCount();
        if (total <= maxBars) {
            return source;
        }

        int start = Math.max(0, total - maxBars);
        // Keep the original Num type (DecimalNum vs DoubleNum) to avoid ta4j type mismatch.
        return source.getSubSeries(start, total);
    }

    private ModelEndpoint resolveEndpoint(String key) {
        return switch (key) {
            case "deepseek" -> new ModelEndpoint("deepseek", deepseekBaseUrl, deepseekModel);
            case "qwen" -> new ModelEndpoint("qwen", qwenBaseUrl, qwenModel);
            case "phi" -> new ModelEndpoint("phi", phiBaseUrl, phiModel);
            default -> throw new IllegalArgumentException("Unknown model key: " + key + ". Allowed: deepseek, qwen, phi");
        };
    }

    private String safeUpper(String value, String fallback) {
        return value == null ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private Double normalizeConfidence(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String nonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record ModelEndpoint(String key, String baseUrl, String model) {}
}
