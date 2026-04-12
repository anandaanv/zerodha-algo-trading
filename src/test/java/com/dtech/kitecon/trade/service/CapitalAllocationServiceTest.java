package com.dtech.kitecon.trade.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class CapitalAllocationServiceTest {

    @InjectMocks
    private CapitalAllocationService capitalAllocationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(capitalAllocationService, "portfolioValue", 500000L);
    }

    @Test
    void testComputeQuantityForEQ() {
        // EQ with lotSize=1, portfolioValue=500000, capitalPct=2%, price=1000
        // capital = 500000 * 0.02 = 10000
        // maxUnits = 10000 / 1000 = 10
        int qty = capitalAllocationService.computeQuantity(
                BigDecimal.valueOf(2.0), BigDecimal.valueOf(1000), 1);
        assertEquals(10, qty);
    }

    @Test
    void testComputeQuantityForFUT_OneLot() {
        // FUT with lotSize=75, portfolioValue=500000, capitalPct=2%, price=100
        // capital = 500000 * 0.02 = 10000
        // maxUnits = 10000 / 100 = 100
        // numLots = 100 / 75 = 1
        // qty = 1 * 75 = 75
        int qty = capitalAllocationService.computeQuantity(
                BigDecimal.valueOf(2.0), BigDecimal.valueOf(100), 75);
        assertEquals(75, qty);
    }

    @Test
    void testComputeQuantityForFUT_MultipleLots() {
        // FUT with lotSize=75, portfolioValue=500000, capitalPct=10%, price=50
        // capital = 500000 * 0.10 = 50000
        // maxUnits = 50000 / 50 = 1000
        // numLots = 1000 / 75 = 13
        // qty = 13 * 75 = 975
        int qty = capitalAllocationService.computeQuantity(
                BigDecimal.valueOf(10.0), BigDecimal.valueOf(50), 75);
        assertEquals(975, qty);
    }

    @Test
    void testComputeQuantityMinimum() {
        // With very small capital, should return at least 1
        int qty = capitalAllocationService.computeQuantity(
                BigDecimal.valueOf(0.01), BigDecimal.valueOf(10000), 1);
        assertEquals(1, qty);
    }

    @Test
    void testComputeQuantityLotSizeMinimum() {
        // With lotSize > 1, should return at least 1 lot
        int qty = capitalAllocationService.computeQuantity(
                BigDecimal.valueOf(0.01), BigDecimal.valueOf(10000), 50);
        assertEquals(50, qty);
    }
}
