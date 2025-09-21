package com.dtech.algo.screener.web;

import com.dtech.algo.screener.enums.SeriesEnum;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Metadata endpoints for Screener UI.
 */
@RestController
@RequestMapping("/api/screener-meta")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class ScreenerMetaController {

    /**
     * Returns list of SeriesEnum display values (e.g., "SPOT", "FUT1", "CE1", "CE-1", ...).
     */
    @GetMapping("/series-enums")
    public List<String> getSeriesEnums() {
        return Arrays.stream(SeriesEnum.values())
                .map(SeriesEnum::toJson)
                .toList();
    }
}
