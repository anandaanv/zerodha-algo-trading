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

    /**
     * Returns impulse-labeled pivots from the historical labeler.
     * Shows where wave3 starts are detected on the confirmed ZigZag.
     */
    @GetMapping("/impulse-labels")
    public ResponseEntity<?> getImpulseLabels(
            @RequestParam("symbol") String symbol,
            @RequestParam("timeframe") String timeframe) {
        try {
            Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
            if (instrument == null) return ResponseEntity.badRequest().build();

            Interval interval = Interval.valueOf(timeframe);
            ZigZagParams params = zigZagService.resolveParams(symbol, interval);
            ZigZagParams backtestParams = ZigZagParams.ofDefaults(
                    params.getAtrLength(), params.getAtrMult(),
                    params.getPctMin(), params.getHysteresis(),
                    params.getMinBarsBetweenPivots(),
                    params.isDynamicPctEnabled(), params.getVolMult(),
                    params.getRvolWindow(), ZigZagParams.Mode.BACKTEST);

            // Get bar series
            org.ta4j.core.BarSeries series = zigZagService.getBarSeries(symbol, instrument, interval);
            if (series == null || series.isEmpty()) return ResponseEntity.ok(java.util.List.of());

            // Detect pivots
            java.util.List<ZigZagPoint> pivots = zigZagService.detect(series, backtestParams);

            // Build bar list and tsToIdx
            java.util.List<org.ta4j.core.Bar> bars = new java.util.ArrayList<>();
            for (int i = 0; i < series.getBarCount(); i++) bars.add(series.getBar(i));
            java.util.Map<Instant, Integer> tsToIdx = new java.util.HashMap<>();
            for (int i = 0; i < bars.size(); i++) tsToIdx.put(bars.get(i).getEndTime(), i);

            // Enrich and label
            com.dtech.kitecon.elliott.ImpulseLabeler labeler = org.springframework.beans.factory.BeanFactoryUtils
                    .beanOfType(org.springframework.web.context.support.WebApplicationContextUtils
                    .getWebApplicationContext(null), com.dtech.kitecon.elliott.ImpulseLabeler.class);

            // Actually, we can't easily get the labeler bean here. Let's just read from the CSV instead.
            // Read the pre-generated training CSV
            String csvPath = "elliott_train_data/" + symbol + "_" + interval.getUiKey() + "_historical-raw_impulse.csv";
            java.io.File csvFile = new java.io.File(csvPath);
            if (!csvFile.exists()) {
                return ResponseEntity.ok(java.util.Map.of("error", "CSV not found. Run training data generation first.", "path", csvPath));
            }

            // Parse CSV to extract impulse labels with timestamps
            java.util.List<java.util.Map<String, Object>> impulseLabels = new java.util.ArrayList<>();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(csvFile))) {
                String header = br.readLine();
                String[] cols = header.split(",");
                int tsIdx = -1, labelIdx = -1, dirIdx = -1, priceIdx = -1;
                for (int i = 0; i < cols.length; i++) {
                    if ("timestamp".equals(cols[i])) tsIdx = i;
                    else if ("label".equals(cols[i])) labelIdx = i;
                    else if ("direction".equals(cols[i])) dirIdx = i;
                    else if ("price".equals(cols[i])) priceIdx = i;
                }

                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",", -1);
                    if (tsIdx >= 0 && labelIdx >= 0 && parts.length > labelIdx) {
                        String label = parts[labelIdx];
                        if ("wave3_start".equals(label) || "wave5_start".equals(label)) {
                            java.util.Map<String, Object> entry = new java.util.HashMap<>();
                            entry.put("timestamp", parts[tsIdx]);
                            entry.put("label", label);
                            entry.put("direction", dirIdx >= 0 ? parts[dirIdx] : "0");
                            entry.put("price", priceIdx >= 0 ? parts[priceIdx] : "0");
                            impulseLabels.add(entry);
                        }
                    }
                }
            }

            return ResponseEntity.ok(impulseLabels);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }
}
