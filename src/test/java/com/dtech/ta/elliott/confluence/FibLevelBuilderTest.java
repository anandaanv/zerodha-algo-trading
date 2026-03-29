package com.dtech.ta.elliott.confluence;

import com.dtech.ta.elliott.swing.Swing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FibLevelBuilderTest {

    private FibLevelBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new FibLevelBuilder();
    }

    private Swing upSwing(double start, double end) {
        return Swing.builder()
                .direction("UP")
                .startPrice(start)
                .endPrice(end)
                .priceChange(end - start)
                .build();
    }

    private Swing downSwing(double start, double end) {
        return Swing.builder()
                .direction("DOWN")
                .startPrice(start)
                .endPrice(end)
                .priceChange(end - start)
                .build();
    }

    @Test
    void retracement_up_swing_correct_prices() {
        Swing swing = upSwing(100, 150); // priceChange = 50
        List<PriceLevel> levels = builder.buildRetracementLevels(swing, 0.005);
        assertEquals(5, levels.size());
        // 23.6% retrace: 150 - 0.236*50 = 138.2
        assertEquals(138.2, levels.get(0).getPrice(), 0.01);
        // 38.2%: 150 - 0.382*50 = 130.9
        assertEquals(130.9, levels.get(1).getPrice(), 0.01);
        // 50%: 125
        assertEquals(125.0, levels.get(2).getPrice(), 0.01);
        // 61.8%: 119.1
        assertEquals(119.1, levels.get(3).getPrice(), 0.01);
        // 78.6%: 150 - 0.786*50 = 110.7
        assertEquals(110.7, levels.get(4).getPrice(), 0.01);
    }

    @Test
    void retracement_down_swing_correct_prices() {
        Swing swing = downSwing(150, 100); // priceChange = -50, absChange = 50
        List<PriceLevel> levels = builder.buildRetracementLevels(swing, 0.005);
        assertEquals(5, levels.size());
        // 23.6% retrace up: 100 + 0.236*50 = 111.8
        assertEquals(111.8, levels.get(0).getPrice(), 0.01);
        // 38.2%: 100 + 0.382*50 = 119.1
        assertEquals(119.1, levels.get(1).getPrice(), 0.01);
    }

    @Test
    void extension_up_base_correct_prices() {
        Swing base = upSwing(100, 150); // absChange = 50
        List<PriceLevel> levels = builder.buildExtensionLevels(base, 130.0, 0.005);
        assertEquals(5, levels.size());
        // 100% ext: 130 + 1.0*50 = 180
        assertEquals(180.0, levels.get(0).getPrice(), 0.01);
        // 127.2%: 130 + 1.272*50 = 193.6
        assertEquals(193.6, levels.get(1).getPrice(), 0.01);
        // 161.8%: 130 + 1.618*50 = 210.9
        assertEquals(210.9, levels.get(2).getPrice(), 0.01);
    }

    @Test
    void retracement_returns_exactly_5_levels() {
        Swing swing = upSwing(100, 200);
        assertEquals(5, builder.buildRetracementLevels(swing, 0.01).size());
    }

    @Test
    void extension_returns_exactly_5_levels() {
        Swing swing = upSwing(100, 200);
        assertEquals(5, builder.buildExtensionLevels(swing, 150.0, 0.01).size());
    }

    @Test
    void labels_contain_RET_and_EXT() {
        Swing swing = upSwing(100, 200);
        builder.buildRetracementLevels(swing, 0.01)
               .forEach(l -> assertTrue(l.getLabel().endsWith("_RET"), "Expected _RET label: " + l.getLabel()));
        builder.buildExtensionLevels(swing, 150.0, 0.01)
               .forEach(l -> assertTrue(l.getLabel().endsWith("_EXT"), "Expected _EXT label: " + l.getLabel()));
    }

    @Test
    void source_types_correct() {
        Swing swing = upSwing(100, 200);
        builder.buildRetracementLevels(swing, 0.01)
               .forEach(l -> assertEquals("FIB_RETRACEMENT", l.getSourceType()));
        builder.buildExtensionLevels(swing, 150.0, 0.01)
               .forEach(l -> assertEquals("FIB_EXTENSION", l.getSourceType()));
    }
}
