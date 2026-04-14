package com.dtech.kitecon.trade.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Client for the RL exit optimizer Flask service.
 * Calls POST /predict-exit to get HOLD/EXIT decisions for active trades.
 * Falls back to null (no RL opinion) if the service is unavailable.
 */
@Service
@Slf4j
public class RlExitClient {

    @Value("${rl.exit.service.url:http://localhost:5001}")
    private String serviceUrl;

    @Value("${rl.exit.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Ask the RL agent whether to exit an active trade.
     *
     * @return "EXIT" if agent recommends exiting, "HOLD" to keep position, null if service unavailable
     */
    public String predict(double entryPrice, double stopLoss, double target, String direction,
                          double currentPrice, double currentHigh, double currentLow,
                          double currentOpen, double currentVolume, int barsHeld,
                          double mfe, double mae,
                          double entryRsi, double entryAdx, double entryMacdHist,
                          double entryBbPctb, double entryBbWidth, double entryStochK,
                          double entryDailyRsi, double entryRr,
                          double rsi, double stochRsiK, double macdHist, double macdCross,
                          double adx, double adxMomentum, double bbPctB, double bbWidth,
                          double rsiSlope, double macdHistSlope, double priceVsEma, double atrRatio) {
        if (!enabled) return null;

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("entry_price", entryPrice);
            body.put("stop_loss", stopLoss);
            body.put("target", target);
            body.put("direction", direction);
            body.put("current_price", currentPrice);
            body.put("current_high", currentHigh);
            body.put("current_low", currentLow);
            body.put("current_open", currentOpen);
            body.put("current_volume", currentVolume);
            body.put("bars_held", barsHeld);
            body.put("mfe", mfe);
            body.put("mae", mae);
            body.put("entry_rsi", entryRsi);
            body.put("entry_adx", entryAdx);
            body.put("entry_macd_hist", entryMacdHist);
            body.put("entry_bb_pctb", entryBbPctb);
            body.put("entry_bb_width", entryBbWidth);
            body.put("entry_stoch_k", entryStochK);
            body.put("entry_daily_rsi", entryDailyRsi);
            body.put("entry_rr", entryRr);
            body.put("rsi", rsi);
            body.put("stoch_rsi_k", stochRsiK);
            body.put("macd_hist", macdHist);
            body.put("macd_cross", macdCross);
            body.put("adx", adx);
            body.put("adx_momentum", adxMomentum);
            body.put("bb_pct_b", bbPctB);
            body.put("bb_width", bbWidth);
            body.put("rsi_slope", rsiSlope);
            body.put("macd_hist_slope", macdHistSlope);
            body.put("price_vs_ema", priceVsEma);
            body.put("atr_ratio", atrRatio);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    serviceUrl + "/predict-exit", request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String action = (String) response.getBody().get("action");
                Object conf = response.getBody().get("confidence");
                double confidence = conf != null ? ((Number) conf).doubleValue() : 0;
                log.debug("[RlExit] prediction={} confidence={}", action, confidence);
                return action;
            }
        } catch (Exception e) {
            log.warn("[RlExit] Service unavailable: {}", e.getMessage());
        }
        return null;
    }
}
