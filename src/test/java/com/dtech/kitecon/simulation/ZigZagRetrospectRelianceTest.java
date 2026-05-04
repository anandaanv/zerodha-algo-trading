package com.dtech.kitecon.simulation;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.KiteconApplication;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

/**
 * Boots the Spring context and runs ZigZagRetrospectService against RELIANCE 1h
 * to measure how many confirmed pivots the new candle-pattern recognizer captures.
 *
 * Run: ./gradlew test --tests "*.ZigZagRetrospectRelianceTest"
 */
@Slf4j
@SpringBootTest(classes = KiteconApplication.class)
@ActiveProfiles("integration")
class ZigZagRetrospectRelianceTest {

    @Autowired
    private ZigZagRetrospectService retrospectService;

    @Test
    void retrospect_RELIANCE_1h() {
        Map<String, Object> result = retrospectService.retrospect("RELIANCE", Interval.OneHour);

        Object totalBars = result.get("totalBars");
        Object total = result.get("totalPivots");
        Object matched = result.get("matched");
        Object unmatched = result.get("unmatched");
        Object pct = result.get("matchedPct");

        log.info("=== RELIANCE 1h zigzag retrospect ===");
        log.info("totalBars={}  totalPivots={}  matched={}  unmatched={}  matchedPct={}%",
                totalBars, total, matched, unmatched, pct);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> misses = (List<Map<String, Object>>) result.get("sampleMisses");
        log.info("Sample of {} unmatched pivots (first 50):", misses == null ? 0 : misses.size());
        if (misses != null) {
            for (Map<String, Object> m : misses) {
                log.info("  bar={} ts={} type={} pivot={} O={} H={} L={} C={}",
                        m.get("bar"), m.get("ts"), m.get("type"), m.get("pivotPrice"),
                        m.get("O"), m.get("H"), m.get("L"), m.get("C"));
            }
        }
    }
}
