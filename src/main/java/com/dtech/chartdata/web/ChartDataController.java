package com.dtech.chartdata.web;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ohlc")
@CrossOrigin // adjust origins as needed
public class ChartDataController {

    private final ChartDataService chartDataService;

    @GetMapping
    public List<OhlcBarDTO> get(
            @RequestParam @NotBlank String symbol,
            @RequestParam @NotBlank String interval,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        return chartDataService.getBars(symbol, interval, from, to);
    }
}
