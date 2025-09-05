package com.dtech.drawings.web;

import com.dtech.drawings.entity.DrawingEntity;
import com.dtech.drawings.model.DrawingType;
import com.dtech.drawings.service.DrawingService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/drawings")
@CrossOrigin // adjust origins if needed
public class DrawingController {

    private final DrawingService service;

    @GetMapping
    public List<DrawingEntity> list(
            @RequestParam @NotBlank String symbol,
            @RequestParam @NotBlank String timeframe,
            @RequestParam(required = false) String userId) {
        return service.list(userId, symbol, timeframe);
    }

    @PostMapping
    public DrawingEntity create(@RequestBody CreateDrawingRequest req) {
        return service.create(req.getUserId(), req.getSymbol(), req.getTimeframe(),
                req.getType(), req.getName(), req.getPayloadJson());
    }

    @PutMapping("/{id}")
    public ResponseEntity<DrawingEntity> update(@PathVariable Long id, @RequestBody UpdateDrawingRequest req) {
        return service.update(id, req.getName(), req.getPayloadJson())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Data
    public static class CreateDrawingRequest {
        @NotBlank private String symbol;
        @NotBlank private String timeframe;
        private String userId;
        private DrawingType type;
        private String name;
        @NotBlank private String payloadJson;
    }

    @Data
    public static class UpdateDrawingRequest {
        private String name;
        private String payloadJson;
    }
}
