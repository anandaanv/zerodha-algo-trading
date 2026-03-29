package com.dtech.ta.elliott.confluence;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class HorizontalSRBuilder {

    /**
     * Cluster pivots whose prices fall within tolerancePct of each other.
     * Returns one PriceLevel per cluster (centroid price, tolerance = centroid * tolerancePct).
     */
    public List<PriceLevel> buildSRLevels(List<ZigZagPoint> pivots, double tolerancePct) {
        if (pivots == null || pivots.isEmpty()) return List.of();

        // Sort by price ascending for easy clustering
        List<ZigZagPoint> sorted = new ArrayList<>(pivots);
        sorted.sort(Comparator.comparingDouble(ZigZagPoint::getValue));

        // Each cluster: list of prices
        List<List<Double>> clusters = new ArrayList<>();

        for (ZigZagPoint p : sorted) {
            double price = p.getValue();
            boolean added = false;
            for (List<Double> cluster : clusters) {
                double centroid = cluster.stream().mapToDouble(d -> d).average().orElse(0);
                double tol = centroid * tolerancePct;
                if (Math.abs(price - centroid) <= tol) {
                    cluster.add(price);
                    added = true;
                    break;
                }
            }
            if (!added) {
                List<Double> newCluster = new ArrayList<>();
                newCluster.add(price);
                clusters.add(newCluster);
            }
        }

        List<PriceLevel> result = new ArrayList<>();
        int idx = 1;
        for (List<Double> cluster : clusters) {
            double centroid = cluster.stream().mapToDouble(d -> d).average().orElse(0);
            double tol = centroid * tolerancePct;
            int count = cluster.size();
            result.add(PriceLevel.builder()
                    .price(centroid)
                    .label("SR_cluster_" + count + "_" + idx)
                    .sourceType("HORIZONTAL_SR")
                    .tolerance(tol)
                    .build());
            idx++;
        }
        return result;
    }
}
