package com.dtech.aitrader.v2.narrative.beat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class BeatSerializationTest {
  private final ObjectMapper objectMapper = new ObjectMapper()
    .registerModule(new ParameterNamesModule());

  @Test
  void testPeakedBeatSerialization() throws Exception {
    PriceContext priceContext =
        PriceContext.builder()
            .swingState(SwingState.HH)
            .vsEvent("breakout")
            .priceValue(1478.9)
            .build();

    Beat beat =
        Beat.builder()
            .what(BeatVerb.PEAKED)
            .component(IndicatorComponent.MACD_LINE)
            .whenBar(165)
            .whenDate("2024-03-01")
            .value(73.37)
            .significance(1.0)
            .consequence(Consequence.CONFIRMED)
            .priceContext(priceContext)
            .tier(Tier.HISTORY)
            .ref("macd_pk_165")
            .note("Peak detected")
            .build();

    String json = objectMapper.writeValueAsString(beat);

    // Assert lowercase verb
    assertTrue(json.contains("\"what\":\"peaked\""));

    // Assert snake_case keys
    assertTrue(json.contains("\"when_bar\""));
    assertTrue(json.contains("\"when_date\""));
    assertTrue(json.contains("\"price_context\""));
    assertTrue(json.contains("\"swing_state\""));
    assertTrue(json.contains("\"vs_event\""));
    assertTrue(json.contains("\"price_value\""));

    // Assert no nulls in JSON
    assertFalse(json.contains("\":null"));

    // Assert consequence is lowercase
    assertTrue(json.contains("\"consequence\":\"confirmed\""));
  }

  @Test
  void testDivergedFromPriceBeatSerialization() throws Exception {
    PivotPairEntry pivot1 =
        PivotPairEntry.builder().bar(100).date("2024-02-15").price(1450.0).macd(65.5).build();
    PivotPairEntry pivot2 =
        PivotPairEntry.builder().bar(120).date("2024-02-20").price(1460.0).macd(72.0).build();

    PivotPairEntry deeperAnchor =
        PivotPairEntry.builder().bar(80).date("2024-02-10").price(1440.0).macd(58.0).build();

    PriceContext priceContext =
        PriceContext.builder()
            .swingState(SwingState.LH)
            .vsEvent("recovery")
            .priceValue(1475.0)
            .build();

    Beat beat =
        Beat.builder()
            .what(BeatVerb.DIVERGED_FROM_PRICE)
            .component(IndicatorComponent.MACD_LINE)
            .whenBar(165)
            .whenDate("2024-03-01")
            .significance(0.8)
            .consequence(Consequence.CONFIRMED)
            .priceContext(priceContext)
            .tier(Tier.HISTORY)
            .ref("macd_div_165")
            .type("regular")
            .direction("bullish")
            .pivotPair(Arrays.asList(pivot1, pivot2))
            .deeperAnchor(deeperAnchor)
            .build();

    String json = objectMapper.writeValueAsString(beat);

    // Assert pivot_pair is present
    assertTrue(json.contains("\"pivot_pair\":["));

    // Assert deeper_anchor is present
    assertTrue(json.contains("\"deeper_anchor\":{"));

    // Assert type and direction
    assertTrue(json.contains("\"type\":\"regular\""));
    assertTrue(json.contains("\"direction\":\"bullish\""));

    // Parse back to verify structure
    Beat parsedBeat = objectMapper.readValue(json, Beat.class);
    assertEquals(BeatVerb.DIVERGED_FROM_PRICE, parsedBeat.getWhat());
    assertEquals(2, parsedBeat.getPivotPair().size());
    assertNotNull(parsedBeat.getDeeperAnchor());
  }

  @Test
  void testCurrentlyBeatSerialization() throws Exception {
    PriceContext priceContext =
        PriceContext.builder()
            .swingState(SwingState.HL)
            .vsEvent("within_range")
            .priceValue(1480.0)
            .build();

    Beat beat =
        Beat.builder()
            .what(BeatVerb.CURRENTLY)
            .component(IndicatorComponent.MACD_ALL)
            .whenBar(165)
            .whenDate("2024-03-01")
            .macdLine(75.5)
            .signalLine(72.0)
            .histogram(3.5)
            .consequence(Consequence.ONGOING)
            .priceContext(priceContext)
            .tier(Tier.PRESENT)
            .ref("macd_now_165")
            .build();

    String json = objectMapper.writeValueAsString(beat);

    // Assert component is macd_all
    assertTrue(json.contains("\"component\":\"macd_all\""));

    // Assert macd_line, signal_line, histogram are present
    assertTrue(json.contains("\"macd_line\""));
    assertTrue(json.contains("\"signal_line\""));
    assertTrue(json.contains("\"histogram\""));

    // Assert value is NOT present for currently
    assertFalse(json.contains("\"value\""));

    // Parse back and verify
    Beat parsedBeat = objectMapper.readValue(json, Beat.class);
    assertEquals(75.5, parsedBeat.getMacdLine());
    assertEquals(72.0, parsedBeat.getSignalLine());
    assertEquals(3.5, parsedBeat.getHistogram());
    assertNull(parsedBeat.getValue());
  }

  @Test
  void testRoundTripSerialization() throws Exception {
    PriceContext priceContext =
        PriceContext.builder()
            .swingState(SwingState.HH)
            .vsEvent("breakout")
            .priceValue(1478.9)
            .build();

    Beat originalBeat =
        Beat.builder()
            .what(BeatVerb.PEAKED)
            .component(IndicatorComponent.MACD_LINE)
            .whenBar(165)
            .whenDate("2024-03-01")
            .value(73.37)
            .significance(1.0)
            .consequence(Consequence.CONFIRMED)
            .priceContext(priceContext)
            .tier(Tier.HISTORY)
            .ref("macd_pk_165")
            .note("Peak detected")
            .build();

    String json = objectMapper.writeValueAsString(originalBeat);
    Beat deserializedBeat = objectMapper.readValue(json, Beat.class);

    assertEquals(originalBeat.getWhat(), deserializedBeat.getWhat());
    assertEquals(originalBeat.getComponent(), deserializedBeat.getComponent());
    assertEquals(originalBeat.getWhenBar(), deserializedBeat.getWhenBar());
    assertEquals(originalBeat.getWhenDate(), deserializedBeat.getWhenDate());
    assertEquals(originalBeat.getValue(), deserializedBeat.getValue());
    assertEquals(originalBeat.getSignificance(), deserializedBeat.getSignificance());
    assertEquals(originalBeat.getConsequence(), deserializedBeat.getConsequence());
    assertEquals(originalBeat.getTier(), deserializedBeat.getTier());
    assertEquals(originalBeat.getRef(), deserializedBeat.getRef());
    assertEquals(originalBeat.getNote(), deserializedBeat.getNote());
  }

  @Test
  void testNarrativeWithTiersSerialization() throws Exception {
    // Create beats for each tier
    PriceContext historyContext =
        PriceContext.builder()
            .swingState(SwingState.HH)
            .vsEvent("breakout")
            .priceValue(1470.0)
            .build();

    Beat historyBeat =
        Beat.builder()
            .what(BeatVerb.PEAKED)
            .component(IndicatorComponent.MACD_LINE)
            .whenBar(100)
            .whenDate("2024-02-15")
            .value(70.0)
            .significance(0.9)
            .consequence(Consequence.CONFIRMED)
            .priceContext(historyContext)
            .tier(Tier.HISTORY)
            .ref("macd_pk_100")
            .build();

    PriceContext recentContext =
        PriceContext.builder()
            .swingState(SwingState.HL)
            .vsEvent("recovery")
            .priceValue(1475.0)
            .build();

    Beat recentBeat =
        Beat.builder()
            .what(BeatVerb.CROSSED)
            .component(IndicatorComponent.SIGNAL_LINE)
            .whenBar(155)
            .whenDate("2024-02-28")
            .consequence(Consequence.CONFIRMED)
            .priceContext(recentContext)
            .tier(Tier.RECENT)
            .ref("macd_cross_155")
            .from("below_signal")
            .to("above_signal")
            .build();

    PriceContext presentContext =
        PriceContext.builder()
            .swingState(SwingState.LH)
            .vsEvent("within_range")
            .priceValue(1478.0)
            .build();

    Beat presentBeat =
        Beat.builder()
            .what(BeatVerb.CURRENTLY)
            .component(IndicatorComponent.MACD_ALL)
            .whenBar(165)
            .whenDate("2024-03-01")
            .macdLine(74.5)
            .signalLine(71.0)
            .histogram(3.5)
            .consequence(Consequence.ONGOING)
            .priceContext(presentContext)
            .tier(Tier.PRESENT)
            .ref("macd_now_165")
            .build();

    Tiers tiers =
        Tiers.builder()
            .history(Arrays.asList(historyBeat))
            .recent(Arrays.asList(recentBeat))
            .present(Arrays.asList(presentBeat))
            .build();

    Checkpoint checkpoint1 =
        Checkpoint.builder().bar(160).macdLine(73.0).signalLine(70.5).histogram(2.5).build();
    Checkpoint checkpoint2 =
        Checkpoint.builder().bar(165).macdLine(74.5).signalLine(71.0).histogram(3.5).build();

    VerificationSlices verificationSlices =
        VerificationSlices.builder()
            .comment("Verified against 5-day lookback")
            .checkpoints(Arrays.asList(checkpoint1, checkpoint2))
            .build();

    MacdParams params = MacdParams.builder().fast(12).slow(26).signal(9).build();

    LastBar lastBar = LastBar.builder().index(165).date("2024-03-01").close(1478.5).build();

    Narrative narrative =
        Narrative.builder()
            .indicator("MACD")
            .params(params)
            .symbol("RELIANCE")
            .timeframe("day")
            .bar0Date("2024-02-01")
            .lastBar(lastBar)
            .calcNote("Daily MACD analysis")
            .tiers(tiers)
            .verificationSlices(verificationSlices)
            .build();

    String json = objectMapper.writeValueAsString(narrative);

    // Assert top-level keys
    assertTrue(json.contains("\"indicator\""));
    assertTrue(json.contains("\"params\""));
    assertTrue(json.contains("\"symbol\""));
    assertTrue(json.contains("\"timeframe\""));
    assertTrue(json.contains("\"bar0_date\""));
    assertTrue(json.contains("\"last_bar\""));
    assertTrue(json.contains("\"calc_note\""));
    assertTrue(json.contains("\"tiers\""));
    assertTrue(json.contains("\"verification_slices\""));

    // Assert tiers has history, recent, present
    assertTrue(json.contains("\"history\":["));
    assertTrue(json.contains("\"recent\":["));
    assertTrue(json.contains("\"present\":["));

    // Assert params fields
    assertTrue(json.contains("\"fast\":12"));
    assertTrue(json.contains("\"slow\":26"));
    assertTrue(json.contains("\"signal\":9"));

    // Parse back and verify structure
    Narrative parsedNarrative = objectMapper.readValue(json, Narrative.class);
    assertEquals("MACD", parsedNarrative.getIndicator());
    assertEquals("RELIANCE", parsedNarrative.getSymbol());
    assertEquals("day", parsedNarrative.getTimeframe());
    assertEquals(1, parsedNarrative.getTiers().getHistory().size());
    assertEquals(1, parsedNarrative.getTiers().getRecent().size());
    assertEquals(1, parsedNarrative.getTiers().getPresent().size());
    assertEquals(2, parsedNarrative.getVerificationSlices().getCheckpoints().size());
  }
}
