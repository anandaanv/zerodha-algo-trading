package com.dtech.algo.screener.web;

import com.dtech.algo.screener.ScreenerService;
import com.dtech.algo.screener.db.Screener;
import com.dtech.algo.screener.db.ScreenerRepository;
import com.dtech.algo.screener.web.dto.ScreenerResponse;
import com.dtech.algo.screener.web.dto.ScreenerUpsertRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/screeners")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class ScreenerController {

    private final ScreenerRepository screenerRepository;
    private final ObjectMapper objectMapper;
    private final ScreenerService screenerService;

    @GetMapping
    public List<ScreenerResponse> list() {
        return screenerRepository.findAll().stream().map(ScreenerResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ScreenerResponse get(@PathVariable long id) {
        Screener s = screenerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Screener not found: " + id));
        return ScreenerResponse.from(s);
    }

    @PostMapping
    public ScreenerResponse create(@RequestBody ScreenerUpsertRequest request) {
        Screener s = new Screener();
        applyUpsert(s, request);
        s.setDirty(true);
        Screener saved = screenerRepository.save(s);
        return ScreenerResponse.from(saved);
    }

    @PutMapping("/{id}")
    public ScreenerResponse update(@PathVariable long id, @RequestBody ScreenerUpsertRequest request) {
        Screener s = screenerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Screener not found: " + id));
        applyUpsert(s, request);
        s.setDirty(true);
        Screener saved = screenerRepository.save(s);
        return ScreenerResponse.from(saved);
    }

    @PostMapping("/{id}/run")
    public void run(@PathVariable long id,
                    @RequestParam String symbol,
                    @RequestParam int nowIndex,
                    @RequestParam(required = false) String timeframe) {
        screenerService.run(id, symbol, nowIndex, timeframe, null);
    }

    private void applyUpsert(Screener s, ScreenerUpsertRequest request) {
        s.setScript(request.getScript());
        s.setTimeframe(request.getTimeframe());
        s.setConfigJson(buildConfigJson(request));
        s.setPromptJson(request.getPromptJson());
        s.setChartsJson(writeJsonArray(request.getCharts()));
    }

    private String buildConfigJson(ScreenerUpsertRequest request) {
        Map<String, Object> cfg = new HashMap<>();
        if (request.getMapping() != null) cfg.put("mapping", request.getMapping());
        if (request.getWorkflow() != null) cfg.put("workflow", request.getWorkflow());
        try {
            return objectMapper.writeValueAsString(cfg);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid config JSON: " + e.getMessage(), e);
        }
    }

    private String writeJsonArray(List<String> values) {
        if (values == null) return "[]";
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid array JSON: " + e.getMessage(), e);
        }
    }
}
