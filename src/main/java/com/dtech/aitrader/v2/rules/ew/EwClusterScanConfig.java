package com.dtech.aitrader.v2.rules.ew;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires three Spring-managed instances of {@link EwClusterScanRule}, one per TF, per owner
 * direction {@code b954cd6e}: "one rule CLASS, three rule INSTANCES, NOT one instance looping
 * over TFs internally."
 *
 * <p>Each instance has the same band-percent and min-touches defaults (controlled by the
 * {@code rules.ew.cluster.*} properties) but a TF-specific rule-id from {@link EwClusterRuleIds}.
 * Downstream rules can target a specific TF's clusters (the Wk instance feeds
 * {@link EwClusterConfluenceRule}; Day + Hr instances feed Phase 2 pattern cluster-respect
 * queries per directed-query principle {@code 2c1fb814}).
 */
@Configuration
public class EwClusterScanConfig {

    @Value("${rules.ew.cluster.band-pct:2.0}")
    private double bandPct;

    @Value("${rules.ew.cluster.min-touches:3}")
    private int minTouches;

    @Bean
    public EwClusterScanRule weeklyClusterScanRule() {
        return new EwClusterScanRule("Week", EwClusterRuleIds.WK, bandPct, minTouches);
    }

    @Bean
    public EwClusterScanRule dailyClusterScanRule() {
        return new EwClusterScanRule("Day", EwClusterRuleIds.DAY, bandPct, minTouches);
    }

    @Bean
    public EwClusterScanRule hourlyClusterScanRule() {
        return new EwClusterScanRule("OneHour", EwClusterRuleIds.HOUR, bandPct, minTouches);
    }
}
