package com.dtech.kitecon.patternscanner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.HashMap;
import java.util.Map;

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
                        double bbWidthDaily, double bbPctBDaily) {
        if (!enabled) return 1.0;

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("watching_pattern",       watchingPattern);
            payload.put("rr_watching",            pattern.getRrRatio());
            payload.put("rsi_at_p1",              pattern.getRsiAtP1());
            payload.put("rsi_at_p2",              pattern.getRsiAtP2());
            payload.put("macd_hist_at_p1",        0.0);
            payload.put("macd_hist_at_p2",        0.0);
            payload.put("stoch_rsi_k_15m",        0.0);
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
        return 1.0;
    }
}
