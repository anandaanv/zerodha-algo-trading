package com.dtech.forecast;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.dtech.forecast.dto.ForecastResponse;
import com.dtech.forecast.dto.ModelForecast;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForecastService {

    private final ChartDataService chartDataService;

    private static final int CONTEXT_SIZE = 64;

    private static final Map<String, Integer> SIDECAR_PORTS = Map.of(
            "chronos", 11111,
            "lagLlama", 11112,
            "granite", 11113
    );

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final RestTemplate restTemplate = new RestTemplate();

    public ForecastResponse getForecast(String symbol, String interval, int horizon) {
        String intervalName = com.dtech.algo.series.Interval.fromUiKey(interval).name();
        List<OhlcBarDTO> bars = chartDataService.getBars(symbol, intervalName, null, null);
        if (bars.isEmpty()) {
            throw new IllegalStateException("No OHLC data for " + symbol + "/" + interval);
        }

        // Extract last CONTEXT_SIZE close prices
        int start = Math.max(0, bars.size() - CONTEXT_SIZE);
        List<Double> closes = bars.subList(start, bars.size())
                .stream()
                .map(OhlcBarDTO::getClose)
                .collect(Collectors.toList());

        OhlcBarDTO lastBar = bars.get(bars.size() - 1);
        long anchorTs = lastBar.getTime();
        double anchorPrice = lastBar.getClose();
        long intervalSec = intervalToSeconds(interval);

        List<Long> futureTimestamps = IntStream.rangeClosed(1, horizon)
                .mapToLong(i -> anchorTs + i * intervalSec)
                .boxed()
                .collect(Collectors.toList());

        // Call all three sidecars in parallel
        CompletableFuture<ModelForecast> chronosFuture = CompletableFuture.supplyAsync(
                () -> callSidecar("chronos", 11111, closes, horizon), executor);
        CompletableFuture<ModelForecast> llamaFuture = CompletableFuture.supplyAsync(
                () -> callSidecar("lagLlama", 11112, closes, horizon), executor);
        CompletableFuture<ModelForecast> graniteFuture = CompletableFuture.supplyAsync(
                () -> callSidecar("granite", 11113, closes, horizon), executor);

        CompletableFuture.allOf(chronosFuture, llamaFuture, graniteFuture).join();

        return new ForecastResponse(
                symbol, interval, horizon,
                anchorTs, anchorPrice,
                futureTimestamps,
                safeGet(chronosFuture),
                safeGet(llamaFuture),
                safeGet(graniteFuture)
        );
    }

    @SuppressWarnings("unchecked")
    private ModelForecast callSidecar(String name, int port, List<Double> values, int horizon) {
        try {
            String url = "http://localhost:" + port + "/scan";
            Map<String, Object> body = Map.of(
                    "values", values,
                    "prediction_length", horizon
            );
            Map<String, Object> resp = restTemplate.postForObject(url, body, Map.class);
            if (resp == null) return unavailable(name, "empty response");

            return new ModelForecast(
                    name, true, null,
                    toDoubleList((List<?>) resp.get("forecast_low")),
                    toDoubleList((List<?>) resp.get("forecast_median")),
                    toDoubleList((List<?>) resp.get("forecast_high")),
                    toDouble(resp.get("convergence_metric"))
            );
        } catch (Exception e) {
            log.warn("Sidecar {} unavailable: {}", name, e.getMessage());
            return unavailable(name, e.getMessage());
        }
    }

    private ModelForecast unavailable(String name, String error) {
        return new ModelForecast(name, false, error, List.of(), List.of(), List.of(), 0.0);
    }

    private ModelForecast safeGet(CompletableFuture<ModelForecast> future) {
        try { return future.get(); } catch (Exception e) { return unavailable("unknown", e.getMessage()); }
    }

    private List<Double> toDoubleList(List<?> raw) {
        if (raw == null) return List.of();
        return raw.stream().map(v -> v instanceof Number n ? n.doubleValue() : 0.0).collect(Collectors.toList());
    }

    private double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private long intervalToSeconds(String interval) {
        try {
            return com.dtech.algo.series.Interval.fromUiKey(interval).getOffset();
        } catch (IllegalArgumentException e) {
            log.warn("Unknown interval '{}', defaulting to 300s", interval);
            return 300L;
        }
    }
}
