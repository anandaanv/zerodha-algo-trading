package com.dtech.kitecon.backtest;

import com.dtech.kitecon.KiteconApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for Double Top/Bottom backtest on RELIANCE hourly data.
 *
 * Prerequisites:
 * - MySQL running on localhost:3306/algotrading
 * - RELIANCE instrument exists in the instruments table
 * - RELIANCE 1h and 1d candles exist
 *
 * Run with: ./gradlew test --tests "*.DoubleTopBottomBacktestTest"
 */
@SpringBootTest(classes = KiteconApplication.class)
@ActiveProfiles("integration")
class DoubleTopBottomBacktestTest {

    @Autowired
    private DoubleTopBottomBacktestService backtestService;

    @Test
    void runRelianceBacktest() throws Exception {
        String csvPath = "/tmp/reliance_double_topbottom_backtest.csv";
        backtestService.runAndWriteCsv("RELIANCE", csvPath);
        System.out.println("CSV written to: " + csvPath);
        // Just verify file exists and has content
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() > 0);
    }
}
