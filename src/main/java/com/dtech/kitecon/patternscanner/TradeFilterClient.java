package com.dtech.kitecon.patternscanner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * HTTP client for the Python XGBoost trade-filter service.
 * Fail-open: if the service is unreachable, returns 1.0 (pass-through).
 */
@Component
@Slf4j
public class TradeFilterClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${trade.filter.service.url:http://localhost:5001}")
    private String serviceUrl;

    @Value("${trade.filter.enabled:true}")
    private boolean enabled;

    @Value("${trade.filter.dtbhns.service.url:http://localhost:5002}")
    private String dtbHnsServiceUrl;

    @Value("${trade.filter.dtbhns.enabled:true}")
    private boolean dtbHnsEnabled;

    /**
     * Score a pattern using the ML model.
     * @return P(WIN) probability [0,1], or 1.0 on service unavailability (fail-open)
     */
    public double score(PatternDto pattern, String watchingPattern,
                        double dailyRsi, double dailyAdx, double dailyAdxEma,
                        double adxWatching, double adxWatchingEma,
                        double adxConfirm, double adxConfirmEma,
                        double macdWatching, double macdSignalWatching,
                        double bbWidthWatching, double bbPctBWatching,
                        double macdDaily, double macdSignalDaily,
                        double bbWidthDaily, double bbPctBDaily,
                        double bbExpanding, double bbAligned,
                        double rsiSlope, double macdHistSlope, double adxSlope) {
        if (!enabled) return 0.9999;

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("watching_pattern",       watchingPattern);
            payload.put("rr_watching",            pattern.getRrRatio());
            payload.put("rsi_at_p1",              pattern.getRsiAtP1());
            payload.put("rsi_at_p2",              pattern.getRsiAtP2());
            payload.put("macd_hist_at_p1",        pattern.getMacdHistAtP1());
            payload.put("macd_hist_at_p2",        pattern.getMacdHistAtP2());
            payload.put("stoch_rsi_k_15m",        pattern.getStochRsiK());
            payload.put("daily_rsi",              dailyRsi);
            payload.put("watching_pattern_height",pattern.getPatternHeight());
            payload.put("e_symmetry",             0.0);
            payload.put("bb_peak_bbw",            0.0);
            payload.put("daily_adx",              dailyAdx);
            payload.put("daily_adx_ema",          dailyAdxEma);
            payload.put("adx_watching",           adxWatching);
            payload.put("adx_watching_ema",       adxWatchingEma);
            payload.put("adx_confirm",            adxConfirm);
            payload.put("adx_confirm_ema",        adxConfirmEma);
            payload.put("macd_watching",          macdWatching);
            payload.put("macd_signal_watching",   macdSignalWatching);
            payload.put("bb_width_watching",      bbWidthWatching);
            payload.put("bb_pct_b_watching",      bbPctBWatching);
            payload.put("macd_daily",             macdDaily);
            payload.put("macd_signal_daily",      macdSignalDaily);
            payload.put("bb_width_daily",         bbWidthDaily);
            payload.put("bb_pct_b_daily",         bbPctBDaily);
            payload.put("bb_expanding",           bbExpanding);
            payload.put("bb_aligned",             bbAligned);
            payload.put("rsi_slope",              rsiSlope);
            payload.put("macd_hist_slope",        macdHistSlope);
            payload.put("adx_slope",              adxSlope);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    serviceUrl + "/predict", payload, Map.class);

            if (response != null && response.containsKey("prob")) {
                return ((Number) response.get("prob")).doubleValue();
            }
        } catch (ResourceAccessException e) {
            log.warn("[TradeFilter] Service unavailable — failing open: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[TradeFilter] Scoring failed — failing open: {}", e.getMessage());
        }
        return 0.9999;
    }

    /**
     * Score DTB+HNS entry using the ML model.
     * @param features 400-element double array
     * @return OptionalDouble with score [0,1], or empty on service unavailability
     */
    public OptionalDouble scoreDtbHns(double[] features) {
        if (!dtbHnsEnabled) return OptionalDouble.empty();
        try {
            Map<String, Object> body = new HashMap<>();
            List<Double> list = new ArrayList<>(features.length);
            for (double f : features) list.add(f);
            body.put("features", list);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    dtbHnsServiceUrl + "/score-dtb-hns", entity, Map.class);
            if (response == null || !response.containsKey("score")) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(((Number) response.get("score")).doubleValue());
        } catch (Exception e) {
            log.warn("[TradeFilter] scoreDtbHns failed: {}", e.toString());
            return OptionalDouble.empty();
        }
    }
}
