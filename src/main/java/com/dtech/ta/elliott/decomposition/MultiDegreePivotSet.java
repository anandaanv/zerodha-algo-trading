package com.dtech.ta.elliott.decomposition;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiDegreePivotSet {
    private String symbol;
    private String timeframe;
    private List<ZigZagPoint> finePivots;
    private List<ZigZagPoint> mediumPivots;
    private List<ZigZagPoint> coarsePivots;

    public List<ZigZagPoint> getByDegree(PivotDegree degree) {
        return switch (degree) {
            case FINE -> finePivots;
            case MEDIUM -> mediumPivots;
            case COARSE -> coarsePivots;
        };
    }
}
