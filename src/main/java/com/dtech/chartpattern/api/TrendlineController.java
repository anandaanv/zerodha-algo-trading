package com.dtech.chartpattern.api;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.api.dto.PatternTrendlinesResponse;
import com.dtech.chartpattern.api.dto.TrendlineResponses;
import com.dtech.chartpattern.api.dto.ZigZagRequests;
import com.dtech.chartpattern.trendline.TrendlineService;
import com.dtech.chartpattern.view.ChartPlotService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/chartpattern/trendline")
@RequiredArgsConstructor
public class TrendlineController {

    private final TrendlineService trendlineService;
    private final InstrumentRepository instrumentRepository;
    private final ChartPlotService chartPlotService;
    private final com.dtech.chartpattern.patterns.PatternTrendlineService patternTrendlineService;

    @PostMapping("/stock")
    public ResponseEntity<List<TrendlineResponses.StockResult>> computeForStock(@RequestBody ZigZagRequests.StockRequest req) {
        Objects.requireNonNull(req.getTradingSymbol(), "tradingSymbol is required");
        Objects.requireNonNull(req.getTimeframes(), "timeframes are required");

        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(req.getTradingSymbol(), new String[]{"NSE"});
        if (instrument == null) {
            return ResponseEntity.badRequest().build();
        }

        List<TrendlineResponses.StockResult> out = new ArrayList<>();
        for (String tfName : req.getTimeframes()) {
            Interval interval = Interval.valueOf(tfName);
            var lines = trendlineService.recalc(req.getTradingSymbol(), interval);
            out.add(TrendlineResponses.StockResult.builder()
                    .tradingSymbol(req.getTradingSymbol())
                    .timeframe(interval.name())
                    .trendlines(lines)
                    .build());
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/index")
    public ResponseEntity<TrendlineResponses.IndexResult> computeForIndex(@RequestBody ZigZagRequests.IndexRequest req) {
        Objects.requireNonNull(req.getIndexName(), "indexName is required");
        Objects.requireNonNull(req.getTimeframes(), "timeframes are required");

        // For index expansion, assume client provides symbols via another service; here we accept a comma-separated list in indexes map if available.
        List<String> symbols = new ArrayList<>(); // Let client pass in req.indexName that could be actual symbol group in your config if needed.
        // For simplicity, accept indexName as a comma-separated symbols list if not found elsewhere.
        if (req.getIndexName().contains(",")) {
            symbols = Arrays.stream(req.getIndexName().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        } else {
            // If a dedicated index-to-symbols map exists elsewhere, wire it similarly to ZigZagController.
            symbols = List.of(req.getIndexName());
        }

        List<TrendlineResponses.StockResult> results = new ArrayList<>();
        for (String symbol : symbols) {
            var inst = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
            if (inst == null) continue;

            for (String tfName : req.getTimeframes()) {
                Interval interval = Interval.valueOf(tfName);
                var lines = trendlineService.recalc(symbol, interval);
                results.add(TrendlineResponses.StockResult.builder()
                        .tradingSymbol(symbol)
                        .timeframe(interval.name())
                        .trendlines(lines)
                        .build());
            }
        }

        TrendlineResponses.IndexResult response = TrendlineResponses.IndexResult.builder()
                .indexName(req.getIndexName())
                .results(results)
                .build();
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/plot", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> plotTrendlines(@RequestParam("tradingSymbol") String tradingSymbol,
                                                 @RequestParam("timeframe") String timeframe) {
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(tradingSymbol, new String[]{"NSE"});
        if (instrument == null) {
            return ResponseEntity.badRequest().build();
        }
        Interval interval = Interval.valueOf(timeframe);
        byte[] png = chartPlotService.renderTrendlinesChart(tradingSymbol, instrument, interval, 1280, 720);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setContentLength(png.length);
        return ResponseEntity.ok()
                .headers(headers)
                .body(png);
    }

    // New endpoint to compute and return pattern-derived trendlines (triangles/wedges/reversal helpers)
    @GetMapping(value = "/patterns")
    public ResponseEntity<PatternTrendlinesResponse> calculateTrendlines(@RequestParam("tradingSymbol") String tradingSymbol,
                                                 @RequestParam("timeframe") String timeframe) {

        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(tradingSymbol, new String[]{"NSE"});
        if (instrument == null) {
            return ResponseEntity.badRequest().build();
        }
        Interval interval = Interval.valueOf(timeframe);

        var lines = patternTrendlineService.getOrRecalc(tradingSymbol, instrument, interval);

        java.util.List<com.dtech.chartpattern.api.dto.PatternTrendlinesResponse.Line> dtos = new java.util.ArrayList<>();
        for (var pl : lines) {
            dtos.add(com.dtech.chartpattern.api.dto.PatternTrendlinesResponse.Line.builder()
                    .groupId(pl.getGroupId())
                    .patternType(pl.getPatternType().name())
                    .side(pl.getSide().name())
                    .startIdx(pl.getStartIdx())
                    .endIdx(pl.getEndIdx())
                    .startTs(pl.getStartTs())
                    .endTs(pl.getEndTs())
                    .y1(pl.getY1())
                    .y2(pl.getY2())
                    .slopePerBar(pl.getSlopePerBar())
                    .intercept(pl.getIntercept())
                    .confidence(pl.getConfidence())
                    .build());
        }

        com.dtech.chartpattern.api.dto.PatternTrendlinesResponse body =
                com.dtech.chartpattern.api.dto.PatternTrendlinesResponse.builder()
                        .tradingSymbol(tradingSymbol)
                        .timeframe(interval.name())
                        .lines(dtos)
                        .build();

        return ResponseEntity.ok(body);
    }
}
