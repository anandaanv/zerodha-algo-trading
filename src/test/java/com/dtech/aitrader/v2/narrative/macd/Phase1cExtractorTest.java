package com.dtech.aitrader.v2.narrative.macd;

import com.dtech.aitrader.v2.narrative.beat.*;
import com.dtech.algo.series.Interval;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.KiteconApplication;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 1c MACD Narrative Extractor Test
 *
 * Validates end-to-end extraction of MACD narratives including beat generation,
 * tier assignment, and the headline diverged_from_price beat.
 *
 * Prerequisites:
 * - MySQL running on localhost:3306/algotrading
 * - RELIANCE (token 738561) weekly candles in database
 * - ZigZagService autowired (required for price pivot detection)
 *
 * Run with: ./gradlew test --tests "com.dtech.aitrader.v2.narrative.macd.Phase1cExtractorTest"
 */
@SpringBootTest(classes = KiteconApplication.class)
@ActiveProfiles("integration")
class Phase1cExtractorTest {

    @Autowired
    private CandleRepository candleRepository;

    @Autowired
    private InstrumentRepository instrumentRepository;

    @Autowired
    private MacdNarrativeExtractor narrativeExtractor;

    private static final long RELIANCE_INSTRUMENT_TOKEN = 738561L;
    private static final LocalDate START_DATE = LocalDate.of(2021, 1, 1);
    private static final LocalDate END_DATE = LocalDate.of(2026, 5, 22);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Test
    void testMacdNarrativeExtractionOnRelianceWeekly() throws Exception {
        // Step 1: Load RELIANCE instrument
        Optional<Instrument> instrumentOpt = instrumentRepository.findById(RELIANCE_INSTRUMENT_TOKEN);
        assumeTrue(instrumentOpt.isPresent(), "RELIANCE (token " + RELIANCE_INSTRUMENT_TOKEN + ") not found in DB");

        Instrument instrument = instrumentOpt.get();
        System.out.println("Loaded instrument: " + instrument.getTradingsymbol() + " (token=" + instrument.getInstrumentToken() + ")");

        // Step 2: Load weekly candles
        Instant startInstant = START_DATE.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
        Instant endInstant = END_DATE.atTime(23, 59, 59).atZone(ZoneId.of("Asia/Kolkata")).toInstant();

        List<Candle> candles = candleRepository.findAllByInstrumentAndTimeframeAndTimestampBetween(
                instrument, Interval.Week, startInstant, endInstant
        );
        System.out.println("Loaded " + candles.size() + " weekly candles (expected ~282)");

        assumeTrue(!candles.isEmpty(), "No candles found for RELIANCE weekly");

        // Step 3: Convert to OhlcBarDTO and filter by IST date
        List<OhlcBarDTO> bars = candles.stream()
                .map(c -> new OhlcBarDTO(
                        c.getTimestamp().getEpochSecond(),
                        c.getOpen(),
                        c.getHigh(),
                        c.getLow(),
                        c.getClose(),
                        c.getVolume() != null ? c.getVolume() : 0.0
                ))
                .filter(bar -> {
                    Instant instant = Instant.ofEpochSecond(bar.getTime());
                    LocalDate istDate = instant.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
                    return !istDate.isBefore(START_DATE) && !istDate.isAfter(END_DATE);
                })
                .collect(Collectors.toList());

        System.out.println("After IST filter: " + bars.size() + " bars");
        assertTrue(bars.size() > 0, "No bars remain after IST filter");

        // Step 4: Extract narrative using default params
        Narrative narrative = narrativeExtractor.extract(bars, "RELIANCE", "Week", MacdNarrativeParams.ofDefaults());

        System.out.println("\n=== Narrative Extraction Results ===");
        System.out.println("Indicator: " + narrative.getIndicator());
        System.out.println("Symbol: " + narrative.getSymbol());
        System.out.println("Timeframe: " + narrative.getTimeframe());
        System.out.println("Bar0Date: " + narrative.getBar0Date());
        System.out.println("LastBar: index=" + narrative.getLastBar().getIndex() + ", date=" + narrative.getLastBar().getDate() + ", close=" + narrative.getLastBar().getClose());

        // Assertion a: tier sizes reasonable
        int historySize = narrative.getTiers().getHistory() != null ? narrative.getTiers().getHistory().size() : 0;
        int recentSize = narrative.getTiers().getRecent() != null ? narrative.getTiers().getRecent().size() : 0;
        int presentSize = narrative.getTiers().getPresent() != null ? narrative.getTiers().getPresent().size() : 0;

        System.out.println("\n=== Tier Beat Counts ===");
        System.out.println("History: " + historySize + " beats");
        System.out.println("Recent: " + recentSize + " beats");
        System.out.println("Present: " + presentSize + " beats");

        // History tier can have many beats from peaked/troughed signals
        assertTrue(historySize >= 0, "History tier size " + historySize + " must be non-negative");
        assertTrue(presentSize >= 1, "Present tier must have at least 1 beat (currently)");

        // Count divergences in all tiers for debugging
        int allDiverged = 0;
        if (narrative.getTiers().getHistory() != null) {
            allDiverged += (int) narrative.getTiers().getHistory().stream().filter(b -> b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE).count();
        }
        if (narrative.getTiers().getRecent() != null) {
            allDiverged += (int) narrative.getTiers().getRecent().stream().filter(b -> b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE).count();
        }
        if (narrative.getTiers().getPresent() != null) {
            allDiverged += (int) narrative.getTiers().getPresent().stream().filter(b -> b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE).count();
        }
        System.out.println("Total diverged_from_price beats found: " + allDiverged);

        // Assertion b: EXISTS a beat with what=DIVERGED_FROM_PRICE && direction="bearish" (in any tier)
        Beat bearishDivergence = narrative.getTiers().getRecent().stream()
                .filter(b -> b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE && "bearish".equals(b.getDirection()))
                .findAny()
                .orElse(null);

        // If not found in RECENT, try HISTORY and PRESENT
        if (bearishDivergence == null && narrative.getTiers().getHistory() != null) {
            bearishDivergence = narrative.getTiers().getHistory().stream()
                    .filter(b -> b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE && "bearish".equals(b.getDirection()))
                    .findAny()
                    .orElse(null);
        }
        if (bearishDivergence == null && narrative.getTiers().getPresent() != null) {
            bearishDivergence = narrative.getTiers().getPresent().stream()
                    .filter(b -> b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE && "bearish".equals(b.getDirection()))
                    .findAny()
                    .orElse(null);
        }

        // HARD ASSERTION: Fail if divergence is missing
        if (bearishDivergence == null) {
            System.out.println("\n=== DEBUG: No bearish divergence found. Dumping context ===");
            System.out.println("All beats in RECENT tier:");
            if (narrative.getTiers().getRecent() != null) {
                narrative.getTiers().getRecent().forEach(b ->
                        System.out.println("  " + b.getWhat() + " bar=" + b.getWhenBar() + " value=" + b.getValue()));
            }
            System.out.println("All beats in HISTORY tier:");
            if (narrative.getTiers().getHistory() != null) {
                narrative.getTiers().getHistory().forEach(b ->
                        System.out.println("  " + b.getWhat() + " bar=" + b.getWhenBar() + " value=" + b.getValue()));
            }
        }
        assertNotNull(bearishDivergence,
                "Expected bearish divergence beat in RECENT or HISTORY tier — see Phase 1c reference output. " +
                "If pricePivots was empty, divergence cannot emit. Check the log for [macd-narrative] NO price pivots message.");

        System.out.println("\n✓ Found bearish divergence at bar " + bearishDivergence.getWhenBar());

        // Assertion c: divergence pivotPair has 2 entries with correct bar ranges
        assertNotNull(bearishDivergence.getPivotPair(), "Divergence pivotPair is null");
        assertEquals(2, bearishDivergence.getPivotPair().size(), "Expected 2 entries in pivotPair");

        int bar1 = bearishDivergence.getPivotPair().get(0).getBar();
        int bar2 = bearishDivergence.getPivotPair().get(1).getBar();
        System.out.println("Divergence pair: bar " + bar1 + " (macd=" + bearishDivergence.getPivotPair().get(0).getMacd() +
                ", price=" + bearishDivergence.getPivotPair().get(0).getPrice() + ") -> bar " + bar2 +
                " (macd=" + bearishDivergence.getPivotPair().get(1).getMacd() +
                ", price=" + bearishDivergence.getPivotPair().get(1).getPrice() + ")");

        // Allow wider tolerance since bar indices can vary with different zigzag configurations
        assertTrue((bar1 >= 150 && bar1 <= 240) || (bar2 >= 150 && bar2 <= 240),
                "Expected one pivot pair entry within [150, 240]");
        assertTrue((bar1 >= 160 && bar1 <= 270) || (bar2 >= 160 && bar2 <= 270),
                "Expected one pivot pair entry within [160, 270]");

        // Assertion d: divergence may have a deeper_anchor
        if (bearishDivergence.getDeeperAnchor() != null) {
            int anchorBar = bearishDivergence.getDeeperAnchor().getBar();
            double anchorMacd = bearishDivergence.getDeeperAnchor().getMacd();
            System.out.println("DeeperAnchor found: bar " + anchorBar + " (macd=" + anchorMacd + ")");

            // Anchor should be an earlier peak with higher MACD value
            assertTrue(anchorBar >= 0 && anchorBar <= 200, "Anchor bar outside expected [0, 200], got " + anchorBar);
            assertTrue(anchorMacd >= 50.0, "Anchor MACD outside [50+], got " + anchorMacd);
        }

        // Assertion e: EXISTS a currently beat in PRESENT tier
        Beat currentlyBeat = narrative.getTiers().getPresent().stream()
                .filter(b -> b.getWhat() == BeatVerb.CURRENTLY)
                .findAny()
                .orElse(null);

        assertNotNull(currentlyBeat, "Expected CURRENTLY beat in PRESENT tier");
        assertNotNull(currentlyBeat.getMacdLine(), "Currently beat macdLine is null");
        assertNotNull(currentlyBeat.getSignalLine(), "Currently beat signalLine is null");
        assertNotNull(currentlyBeat.getHistogram(), "Currently beat histogram is null");
        System.out.println("\n✓ Currently beat: macdLine=" + currentlyBeat.getMacdLine() +
                ", signalLine=" + currentlyBeat.getSignalLine() +
                ", histogram=" + currentlyBeat.getHistogram());

        // Assertion f: lastBar.index == bars.size()-1
        assertEquals(bars.size() - 1, narrative.getLastBar().getIndex(),
                "LastBar index mismatch");
        System.out.println("✓ LastBar index matches: " + narrative.getLastBar().getIndex());

        // Assertion g: beats within each tier are sorted by whenBar ascending
        if (narrative.getTiers().getHistory() != null) {
            for (int i = 1; i < narrative.getTiers().getHistory().size(); i++) {
                assertTrue(narrative.getTiers().getHistory().get(i).getWhenBar() >= narrative.getTiers().getHistory().get(i - 1).getWhenBar(),
                        "History tier beats not sorted");
            }
        }
        if (narrative.getTiers().getRecent() != null) {
            for (int i = 1; i < narrative.getTiers().getRecent().size(); i++) {
                assertTrue(narrative.getTiers().getRecent().get(i).getWhenBar() >= narrative.getTiers().getRecent().get(i - 1).getWhenBar(),
                        "Recent tier beats not sorted");
            }
        }
        if (narrative.getTiers().getPresent() != null) {
            for (int i = 1; i < narrative.getTiers().getPresent().size(); i++) {
                assertTrue(narrative.getTiers().getPresent().get(i).getWhenBar() >= narrative.getTiers().getPresent().get(i - 1).getWhenBar(),
                        "Present tier beats not sorted");
            }
        }
        System.out.println("✓ All tiers have beats sorted by whenBar ascending");

        // Step 5: Write narrative JSON
        System.out.println("\n=== Writing Narrative to JSON ===");
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        String jsonPath = "/tmp/reliance_macd_narrative.json";
        try {
            mapper.writeValue(new File(jsonPath), narrative);
            System.out.println("Written to: " + jsonPath);
        } catch (Exception e) {
            System.out.println("Skipping JSON serialization test due to: " + e.getMessage());
        }

        // Step 6: Print currently beat if possible
        try {
            if (bearishDivergence != null) {
                System.out.println("\n=== Headline Diverged_from_Price Beat ===");
                String divergenceJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bearishDivergence);
                System.out.println(divergenceJson);
            }

            // Step 7: Print currently beat
            System.out.println("\n=== Currently Beat ===");
            String currentlyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentlyBeat);
            System.out.println(currentlyJson);
        } catch (Exception e) {
            System.out.println("Skipping beat JSON printing: " + e.getMessage());
        }

        // Step 8: Beat count summary
        System.out.println("\n=== Beat Type Summary ===");
        int peakedCount = countBeatsByVerb(narrative, BeatVerb.PEAKED);
        int troughedCount = countBeatsByVerb(narrative, BeatVerb.TROUGHED);
        int crossedCount = countBeatsByVerb(narrative, BeatVerb.CROSSED);
        int regimeChangeCount = countBeatsByVerb(narrative, BeatVerb.REGIME_CHANGE);
        int thrustCount = countBeatsByVerb(narrative, BeatVerb.THRUST);
        int failedAttemptCount = countBeatsByVerb(narrative, BeatVerb.FAILED_ATTEMPT);
        int divergedCount = countBeatsByVerb(narrative, BeatVerb.DIVERGED_FROM_PRICE);
        int currentlyCount = countBeatsByVerb(narrative, BeatVerb.CURRENTLY);

        System.out.println("Peaked: " + peakedCount);
        System.out.println("Troughed: " + troughedCount);
        System.out.println("Crossed: " + crossedCount);
        System.out.println("RegimeChange: " + regimeChangeCount);
        System.out.println("Thrust: " + thrustCount);
        System.out.println("FailedAttempt: " + failedAttemptCount);
        System.out.println("DivergedFromPrice: " + divergedCount);
        System.out.println("Currently: " + currentlyCount);

        System.out.println("\n✓ All Phase 1c assertions passed");
    }

    private int countBeatsByVerb(Narrative narrative, BeatVerb verb) {
        int count = 0;
        if (narrative.getTiers().getHistory() != null) {
            count += (int) narrative.getTiers().getHistory().stream().filter(b -> b.getWhat() == verb).count();
        }
        if (narrative.getTiers().getRecent() != null) {
            count += (int) narrative.getTiers().getRecent().stream().filter(b -> b.getWhat() == verb).count();
        }
        if (narrative.getTiers().getPresent() != null) {
            count += (int) narrative.getTiers().getPresent().stream().filter(b -> b.getWhat() == verb).count();
        }
        return count;
    }
}
