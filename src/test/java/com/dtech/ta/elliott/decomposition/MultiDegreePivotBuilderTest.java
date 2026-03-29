package com.dtech.ta.elliott.decomposition;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.ta4j.core.BarSeries;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MultiDegreePivotBuilderTest {

    private ZigZagService zigZagService;
    private MultiDegreePivotBuilder builder;

    @BeforeEach
    void setUp() {
        zigZagService = Mockito.mock(ZigZagService.class);
        builder = new MultiDegreePivotBuilder(zigZagService);
    }

    private ZigZagPoint pivot(ZigZagPoint.Type type, int barIndex, double price) {
        return ZigZagPoint.builder().type(type).barIndex(barIndex).value(price).build();
    }

    @Test
    void coarse_has_fewer_or_equal_pivots_than_fine() {
        List<ZigZagPoint> fine   = List.of(pivot(ZigZagPoint.Type.LOW, 1, 100), pivot(ZigZagPoint.Type.HIGH, 3, 110),
                                           pivot(ZigZagPoint.Type.LOW, 5, 105), pivot(ZigZagPoint.Type.HIGH, 7, 115));
        List<ZigZagPoint> medium = List.of(pivot(ZigZagPoint.Type.LOW, 1, 100), pivot(ZigZagPoint.Type.HIGH, 5, 115));
        List<ZigZagPoint> coarse = List.of(pivot(ZigZagPoint.Type.LOW, 1, 100), pivot(ZigZagPoint.Type.HIGH, 7, 115));

        // Return different sizes based on call order (fine first, medium second, coarse third)
        when(zigZagService.detect(any(), any()))
                .thenReturn(fine)
                .thenReturn(medium)
                .thenReturn(coarse);

        BarSeries mockSeries = Mockito.mock(BarSeries.class);
        MultiDegreePivotSet result = builder.build(mockSeries, "INFY", "1D");

        assertTrue(result.getCoarsePivots().size() <= result.getMediumPivots().size());
        assertTrue(result.getMediumPivots().size() <= result.getFinePivots().size());
    }

    @Test
    void all_three_degrees_non_empty() {
        List<ZigZagPoint> list = List.of(
                pivot(ZigZagPoint.Type.LOW, 0, 100),
                pivot(ZigZagPoint.Type.HIGH, 10, 120)
        );
        when(zigZagService.detect(any(), any())).thenReturn(list);

        BarSeries mockSeries = Mockito.mock(BarSeries.class);
        MultiDegreePivotSet result = builder.build(mockSeries, "INFY", "1D");

        assertFalse(result.getFinePivots().isEmpty());
        assertFalse(result.getMediumPivots().isEmpty());
        assertFalse(result.getCoarsePivots().isEmpty());
    }

    @Test
    void getByDegree_returns_correct_list() {
        List<ZigZagPoint> fine   = List.of(pivot(ZigZagPoint.Type.LOW, 1, 100));
        List<ZigZagPoint> medium = List.of(pivot(ZigZagPoint.Type.HIGH, 2, 110));
        List<ZigZagPoint> coarse = List.of(pivot(ZigZagPoint.Type.LOW, 3, 90));

        when(zigZagService.detect(any(), any()))
                .thenReturn(fine)
                .thenReturn(medium)
                .thenReturn(coarse);

        BarSeries mockSeries = Mockito.mock(BarSeries.class);
        MultiDegreePivotSet result = builder.build(mockSeries, "X", "1W");

        assertEquals(fine, result.getByDegree(PivotDegree.FINE));
        assertEquals(medium, result.getByDegree(PivotDegree.MEDIUM));
        assertEquals(coarse, result.getByDegree(PivotDegree.COARSE));
    }
}
