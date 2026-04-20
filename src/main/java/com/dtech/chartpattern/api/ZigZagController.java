package com.dtech.chartpattern.api;

import com.dtech.chartpattern.api.dto.ZigZagRequests;
import com.dtech.chartpattern.api.dto.ZigZagResponses;
import com.dtech.chartpattern.config.ChartPatternProperties;
import com.dtech.chartpattern.view.ChartPlotService;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.algo.series.Interval;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.copilot.MarketStructureService;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.simulation.IncrementalZigZag;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.data.Candle;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.Comparator;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chartpattern/zigzag")
@RequiredArgsConstructor
public class ZigZagController {

    private final InstrumentRepository instrumentRepository;
    private final ZigZagService zigZagService;
    private final ChartPatternProperties properties;
    private final ChartPlotService chartPlotService;
    private final MarketStructureService marketStructureService;
    private final CandleRepository candleRepository;

    @PostMapping("/stock")
    public ResponseEntity<List<ZigZagResponses.StockResult>> computeForStock(@RequestBody ZigZagRequests.StockRequest req) {
        Objects.requireNonNull(req.getTradingSymbol(), "tradingSymbol is required");
        Objects.requireNonNull(req.getTimeframes(), "timeframes are required");

        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(req.getTradingSymbol(), new String[]{"NSE"});
        if (instrument == null) {
            return ResponseEntity.badRequest().build();
        }

        List<ZigZagResponses.StockResult> out = new ArrayList<>();
        for (String tfName : req.getTimeframes()) {
            Interval interval = Interval.valueOf(tfName);
            ZigZagParams params = zigZagService.resolveParams(req.getTradingSymbol(), interval);
            List<ZigZagPoint> pivots = zigZagService.detectAndPersist(req.getTradingSymbol(), instrument, interval, req.isPersist());
            out.add(ZigZagResponses.StockResult.builder()
                    .tradingSymbol(req.getTradingSymbol())
                    .timeframe(interval.name())
                    .params(params)
                    .pivots(pivots)
                    .build());
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/index")
    public ResponseEntity<ZigZagResponses.IndexResult> computeForIndex(@RequestBody ZigZagRequests.IndexRequest req) {
        Objects.requireNonNull(req.getIndexName(), "indexName is required");
        Objects.requireNonNull(req.getTimeframes(), "timeframes are required");

        List<String> symbols = properties.getIndexes().getOrDefault(req.getIndexName(), Collections.emptyList());
        if (symbols.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    ZigZagResponses.IndexResult.builder()
                            .indexName(req.getIndexName())
                            .results(Collections.emptyList())
                            .build()
            );
        }

        List<ZigZagResponses.StockResult> results = new ArrayList<>();
        for (String symbol : symbols) {
            Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
            if (instrument == null) continue;

            for (String tfName : req.getTimeframes()) {
                Interval interval = Interval.valueOf(tfName);
                ZigZagParams params = zigZagService.resolveParams(symbol, interval);
                List<ZigZagPoint> pivots = zigZagService.detectAndPersist(symbol, instrument, interval, req.isPersist());
                results.add(ZigZagResponses.StockResult.builder()
                        .tradingSymbol(symbol)
                        .timeframe(interval.name())
                        .params(params)
                        .pivots(pivots)
                        .build());
            }
        }

        ZigZagResponses.IndexResult response = ZigZagResponses.IndexResult.builder()
                .indexName(req.getIndexName())
                .results(results)
                .build();
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/plot", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> plotChart(@RequestParam("tradingSymbol") String tradingSymbol,
                                            @RequestParam("timeframe") String timeframe) {
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(tradingSymbol, new String[]{"NSE"});
        if (instrument == null) {
            return ResponseEntity.badRequest().build();
        }
        Interval interval = Interval.valueOf(timeframe);
        byte[] png = chartPlotService.renderZigZagChart(tradingSymbol, instrument, interval, 1280, 720);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setContentLength(png.length);
        return ResponseEntity.ok()
                .headers(headers)
                .body(png);
    }

    @GetMapping("/market-structure")
    public ResponseEntity<List<MarketStructurePoint>> getMarketStructure(
            @RequestParam("symbol") String symbol,
            @RequestParam("timeframe") String timeframe) {
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            return ResponseEntity.badRequest().build();
        }
        Interval interval = Interval.valueOf(timeframe);
        List<ZigZagPoint> pivots = zigZagService.detectAndPersist(symbol, instrument, interval, false);
        var data = marketStructureService.analyse(pivots, interval.name());
        return ResponseEntity.ok(data.getSwingPoints());
    }

    /**
     * Returns IncrementalZigZag pivots — what the simulation/live scanner sees.
     * Compare with /stock endpoint (confirmed pivots) to verify alignment.
     */
    @GetMapping("/incremental")
    public ResponseEntity<List<ZigZagPoint>> getIncrementalPivots(
            @RequestParam("symbol") String symbol,
            @RequestParam("timeframe") String timeframe) {
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            return ResponseEntity.badRequest().build();
        }
        Interval interval = Interval.valueOf(timeframe);
        ZigZagParams params = zigZagService.resolveParams(symbol, interval);
        ZigZagParams backtestParams = ZigZagParams.ofDefaults(
                params.getAtrLength(), params.getAtrMult(),
                params.getPctMin(), params.getHysteresis(),
                params.getMinBarsBetweenPivots(),
                params.isDynamicPctEnabled(), params.getVolMult(),
                params.getRvolWindow(), ZigZagParams.Mode.BACKTEST);

        // Load all bars from DB
        Instant from = Instant.parse("2015-01-01T00:00:00Z");
        List<Candle> candles = candleRepository
                .findAllByInstrumentAndTimeframeAndTimestampBetween(instrument, interval, from, Instant.now());
        candles.sort(Comparator.comparing(Candle::getTimestamp));

        // Process bars through IncrementalZigZag (matching simulation behavior)
        IncrementalZigZag izz = new IncrementalZigZag(backtestParams);
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            org.ta4j.core.Bar bar = BarsLoader.getBar(
                    c.getOpen(), c.getHigh(), c.getLow(), c.getClose(),
                    java.util.Optional.ofNullable(c.getVolume()).orElse(0L), c.getTimestamp());
            izz.processBar(bar, i);
        }

        // Return pivots with trailing extreme
        if (candles.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        Candle lastCandle = candles.get(candles.size() - 1);
        org.ta4j.core.Bar lastBar = BarsLoader.getBar(
                lastCandle.getOpen(), lastCandle.getHigh(), lastCandle.getLow(), lastCandle.getClose(),
                java.util.Optional.ofNullable(lastCandle.getVolume()).orElse(0L), lastCandle.getTimestamp());
        List<ZigZagPoint> pivots = izz.getPivotsWithTrailingExtreme(lastBar);
        return ResponseEntity.ok(pivots);
    }
}
