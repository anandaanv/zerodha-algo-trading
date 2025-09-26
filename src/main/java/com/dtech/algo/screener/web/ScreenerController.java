package com.dtech.algo.screener.web;

import com.dtech.algo.screener.ScreenerContextLoader;
import com.dtech.algo.screener.ScreenerService;
import com.dtech.algo.screener.db.ScreenerEntity;
import com.dtech.algo.screener.db.ScreenerRepository;
import com.dtech.algo.screener.domain.Screener;
import com.dtech.algo.screener.web.dto.ScreenerResponse;
import com.dtech.algo.screener.web.dto.ScreenerUpsertRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/screeners")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class ScreenerController {

    private final ScreenerRepository screenerRepository;
    private final ObjectMapper objectMapper;
    private final ScreenerService screenerService;
    private final com.dtech.algo.screener.ScreenerRegistryService screenerRegistryService;

    @GetMapping
    public List<ScreenerResponse> list() {
        return screenerRepository.findAll().stream()
                .map(e -> Screener.fromEntity(e, objectMapper))
                .map(d -> ScreenerResponse.fromDomain(d, objectMapper))
                .toList();
    }

    @GetMapping("/{id}")
    public ScreenerResponse get(@PathVariable long id) {
        ScreenerEntity e = screenerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Screener not found: " + id));
        Screener d = Screener.fromEntity(e, objectMapper);
        return ScreenerResponse.fromDomain(d, objectMapper);
    }

    @PostMapping
    public ScreenerResponse create(@RequestBody ScreenerUpsertRequest request) {
        Screener domain = buildDomainFromRequest(request, null);
        ScreenerEntity toSave = domain.toEntity(objectMapper);
        toSave.setDirty(true);
        ScreenerEntity saved = screenerRepository.save(toSave);
        Screener persisted = Screener.fromEntity(saved, objectMapper);
        return ScreenerResponse.fromDomain(persisted, objectMapper);
    }

    @PutMapping("/{id}")
    public ScreenerResponse update(@PathVariable long id, @RequestBody ScreenerUpsertRequest request) {
        ScreenerEntity existing = screenerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Screener not found: " + id));
        Screener existingDomain = Screener.fromEntity(existing, objectMapper);
        Screener incoming = buildDomainFromRequest(request, id);

        Screener merged = existingDomain.toBuilder()
                .script(pick(incoming.getScript(), existingDomain.getScript()))
                .timeframe(pick(incoming.getTimeframe(), existingDomain.getTimeframe()))
                .promptId(pick(incoming.getPromptId(), existingDomain.getPromptId()))
                .promptJson(pick(incoming.getPromptJson(), existingDomain.getPromptJson()))
                .mapping(incoming.getMapping() == null || incoming.getMapping().isEmpty() ? existingDomain.getMapping() : incoming.getMapping())
                .workflow(incoming.getWorkflow() == null || incoming.getWorkflow().isEmpty() ? existingDomain.getWorkflow() : incoming.getWorkflow())
                .charts(incoming.getCharts() == null || incoming.getCharts().isEmpty() ? existingDomain.getCharts() : incoming.getCharts())
                .symbols(existingDomain.getSymbols())
                .schedulingConfig(incoming.getSchedulingConfig() != null ? incoming.getSchedulingConfig() : existingDomain.getSchedulingConfig())
                .build();

        ScreenerEntity toSave = merged.toEntity(objectMapper);
        // Preserve flags/timestamps not set by domain
        toSave.setDirty(true);
        toSave.setDeleted(existing.getDeleted());
        toSave.setCreatedAt(existing.getCreatedAt());
        ScreenerEntity saved = screenerRepository.save(toSave);
        Screener persisted = Screener.fromEntity(saved, objectMapper);
        return ScreenerResponse.fromDomain(persisted, objectMapper);
    }

    @PostMapping("/{id}/run")
    public void run(@PathVariable long id,
                    @RequestParam String symbol,
                    @RequestParam int nowIndex,
                    @RequestParam(required = false) String timeframe) {
        screenerService.run(id, symbol, nowIndex, timeframe, null, 0L);
    }

    /**
     * Validate a screener Kotlin script without persisting anything.
     * Returns { "ok": true } on success or { "ok": false, "error": "..." } on failure.
     */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, String> body) {
        String code = body == null ? null : body.get("script");
        if (code == null || code.isBlank()) {
            return Map.of("ok", false, "error", "Script is empty");
        }
        try {
            screenerRegistryService.validateScript(code);
            return Map.of("ok", true);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null && e.getCause() != null) msg = e.getCause().getMessage();
            return Map.of("ok", false, "error", msg == null ? e.toString() : msg);
        }
    }

    private Screener buildDomainFromRequest(ScreenerUpsertRequest req, Long id) {
        Map<String, ScreenerContextLoader.SeriesSpec> typedMapping = Map.of();
        if (req.getMapping() != null) {
            typedMapping = req.getMapping().entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> objectMapper.convertValue(e.getValue(), ScreenerContextLoader.SeriesSpec.class)
            ));
        }
        List<com.dtech.algo.screener.enums.WorkflowStep> steps = Optional.ofNullable(req.getWorkflow())
                .orElse(List.of()).stream()
                .map(s -> com.dtech.algo.screener.enums.WorkflowStep.valueOf(s))
                .toList();

        com.dtech.algo.screener.model.SchedulingConfig sc = null;
        if (req.getRunConfigs() != null) {
            sc = com.dtech.algo.screener.model.SchedulingConfig.builder()
                    .runConfigs(req.getRunConfigs())
                    .build();
        }

        return Screener.builder()
                .id(id == null ? 0L : id)
                .name(null)
                .script(req.getScript())
                .timeframe(req.getTimeframe())
                .promptId(req.getPromptId())
                .promptJson(req.getPromptJson())
                .mapping(typedMapping)
                .workflow(steps)
                .charts(Optional.ofNullable(req.getCharts()).orElse(List.of()))
                .symbols(List.of())
                .schedulingConfig(sc)
                .build();
    }

    private static String pick(String candidate, String existing) {
        return (candidate != null && !candidate.isBlank()) ? candidate : existing;
    }
}
