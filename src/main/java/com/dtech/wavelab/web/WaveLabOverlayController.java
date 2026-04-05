package com.dtech.wavelab.web;

import com.dtech.wavelab.entity.WlOverlay;
import com.dtech.wavelab.service.WaveLabOverlayService;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/wave-lab/overlays")
@CrossOrigin
public class WaveLabOverlayController {

    private final WaveLabOverlayService service;

    @GetMapping
    public ResponseEntity<List<WlOverlay>> listOverlays(@RequestParam String symbol, @RequestParam String timeframe) {
        return ResponseEntity.ok(service.listOverlays(symbol, timeframe));
    }

    @PostMapping
    public ResponseEntity<WlOverlay> saveOverlay(@RequestBody SaveOverlayRequest request) {
        return ResponseEntity.ok(service.saveOverlay(request.symbol, request.timeframe, request.algoName, request.drawingJson));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOverlay(@PathVariable Long id) {
        service.deleteOverlay(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clearOverlays(@RequestParam String symbol, @RequestParam String timeframe) {
        service.clearOverlays(symbol, timeframe);
        return ResponseEntity.noContent().build();
    }

    @Data
    static class SaveOverlayRequest {
        private String symbol;
        private String timeframe;
        private String algoName;
        private String drawingJson;
    }
}
