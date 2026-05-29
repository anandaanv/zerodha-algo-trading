package com.dtech.aitrader.v2.rules.ew.dwell;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.ta4j.core.BarSeries;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC-010 Phase 1 sub-phase 1e — Hr re-validation per owner Q3 ({@code 59fa728f}):
 * "On Hr, 3 bars = 3 hours = barely a pause. Do NOT assume N=3 on Hr. Re-validate the
 * SAME way the daily was validated: run the dwell detector on a trending stock's Hr data
 * across N in {3,4,5,6} at k=0.8, count shelves, eyeball whether they're real intraday
 * consolidations vs noise. Pick the N where dwell stays self-limiting."
 *
 * <p>Reports per-N counts as test output for the owner to read; no hard assertion on
 * counts (owner picks the Hr default after seeing numbers).
 *
 * <p>Gated by {@code RUN_DWELL_HR_CALIBRATION=1}, integration profile.
 */
@SpringBootTest(classes = com.dtech.kitecon.KiteconApplication.class)
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named = "RUN_DWELL_HR_CALIBRATION", matches = "1")
class DwellPivotHrCalibrationTest {

    private static final String[] STOCKS = {"RELIANCE", "TCS", "INFY", "HDFCBANK"};

    @Autowired private InstrumentRepository instrumentRepository;
    @Autowired private BarsLoader barsLoader;
    @Autowired private DwellPivotDetector dwellPivotDetector;

    @Test
    void hr_dwell_counts_by_N_for_owner_pick() {
        System.out.println("\n══════════════════════════════════════════════════════════════════════");
        System.out.println(" SPEC-010 Phase 1 1e — Hr dwell-pivot N sweep, k=0.8, atrMult=3.0");
        System.out.println(" Per owner Q3 (59fa728f): pick N where dwell stays self-limiting");
        System.out.println("══════════════════════════════════════════════════════════════════════");
        System.out.printf("%n %-10s %-8s %-8s %-8s %-8s %-8s%n",
                "Stock", "Bars", "N=3", "N=4", "N=5", "N=6");
        System.out.println(" " + "-".repeat(55));

        for (String sym : STOCKS) {
            Instrument inst = instrumentRepository.findByTradingsymbolAndExchangeIn(
                    sym, new String[]{"NSE"});
            assertNotNull(inst, "instrument resolution: " + sym);
            BarSeries series;
            try {
                series = barsLoader.loadInstrumentSeries(inst, Interval.OneHour);
            } catch (Exception e) {
                System.out.printf(" %-10s [bar load failed: %s]%n", sym, e.getMessage());
                continue;
            }
            assertNotNull(series, "Hr series for " + sym);
            assertTrue(series.getBarCount() > 100, "need > 100 hourly bars; got " + series.getBarCount());

            int[] counts = new int[4];
            for (int idx = 0; idx < 4; idx++) {
                int N = 3 + idx;
                List<DwellPivot> ds = dwellPivotDetector.detect(series, "OneHour",
                        3.0, 0.8, N,
                        DwellPivotDetector.DEFAULT_LOOKFORWARD_BARS,
                        DwellPivotDetector.DEFAULT_DIRECTION_BREAK_ATR);
                counts[idx] = ds.size();
            }
            System.out.printf(" %-10s %-8d %-8d %-8d %-8d %-8d%n",
                    sym, series.getBarCount(), counts[0], counts[1], counts[2], counts[3]);
        }
        System.out.println("══════════════════════════════════════════════════════════════════════");
        System.out.println(" Pick N: smallest value where Hr stays self-limiting (handful per stock/year");
        System.out.println(" of hourly data, not a firehose). Owner direction (Q3) suggested N=4-6.");
        System.out.println("══════════════════════════════════════════════════════════════════════\n");
    }
}
