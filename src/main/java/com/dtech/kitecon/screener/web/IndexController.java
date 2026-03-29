package com.dtech.kitecon.screener.web;

import com.dtech.kitecon.repository.IndexSymbolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/indices")
@RequiredArgsConstructor
public class IndexController {

    private final IndexSymbolRepository indexSymbolRepository;

    @GetMapping
    public List<Map<String, Object>> listIndices() {
        return indexSymbolRepository.findDistinctIndexNames().stream()
                .map(name -> Map.<String, Object>of(
                        "name", name,
                        "displayName", formatDisplayName(name),
                        "symbolCount", indexSymbolRepository.countByIndexName(name)
                ))
                .collect(Collectors.toList());
    }

    @GetMapping("/{name}/symbols")
    public List<String> getIndexSymbols(@PathVariable String name) {
        return indexSymbolRepository.findAllSymbolsByIndexName(name);
    }

    private String formatDisplayName(String name) {
        // e.g. NIFTY50 -> Nifty 50, BANKNIFTY -> Bank Nifty
        return name.charAt(0) + name.substring(1).toLowerCase()
                .replaceAll("(\\d+)", " $1")
                .trim();
    }
}
