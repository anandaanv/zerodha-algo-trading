package com.dtech.forecast;

import com.dtech.forecast.dto.ForecastResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/forecast")
@RequiredArgsConstructor
public class ForecastController {

    private final ForecastService forecastService;

    @GetMapping
    public ResponseEntity<ForecastResponse> getForecast(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam(defaultValue = "12") int horizon
    ) {
        if (horizon < 1 || horizon > 96) {
            return ResponseEntity.badRequest().build();
        }
        ForecastResponse response = forecastService.getForecast(symbol, interval, horizon);
        return ResponseEntity.ok(response);
    }
}
