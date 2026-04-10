package com.dtech.kitecon.backtest;

import com.dtech.kitecon.KiteconApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.util.List;

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

    @Autowired
    private TriangleBacktestService triangleBacktestService;

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

    @Test
    void runTop20Backtest() throws Exception {
        List<String> top20 = List.of(
            "RELIANCE", "TCS", "HDFCBANK", "BHARTIARTL", "ICICIBANK",
            "INFY", "SBIN", "BAJFINANCE", "HINDUNILVR", "ITC",
            "LT", "KOTAKBANK", "AXISBANK", "ASIANPAINT", "HCLTECH",
            "MARUTI", "SUNPHARMA", "TITAN", "ADANIENT", "NTPC"
        );
        String csvPath = "/tmp/top20_double_topbottom_backtest.csv";
        backtestService.runMultipleAndWriteCsv(top20, csvPath);
        System.out.println("CSV written to: " + csvPath);
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() > 0);
    }

    @Test
    void runRelianceTriangleBacktest() throws Exception {
        String csvPath = "/tmp/reliance_triangle_backtest.csv";
        triangleBacktestService.runAndWriteCsv("RELIANCE", csvPath);
        System.out.println("CSV written to: " + csvPath);
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() > 0);
    }
}
