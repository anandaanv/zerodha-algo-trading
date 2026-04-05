package com.dtech.wavelab.service;

import com.dtech.wavelab.entity.WlOverlay;
import com.dtech.wavelab.repo.WlOverlayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class WaveLabOverlayService {

    private final WlOverlayRepository repo;

    public List<WlOverlay> listOverlays(String symbol, String timeframe) {
        return repo.findBySymbolAndTimeframeOrderByCreatedAtDesc(symbol, timeframe);
    }

    public WlOverlay saveOverlay(String symbol, String timeframe, String algoName, String drawingJson) {
        WlOverlay overlay = WlOverlay.builder()
                .symbol(symbol)
                .timeframe(timeframe)
                .algoName(algoName)
                .drawingJson(drawingJson)
                .build();
        return repo.save(overlay);
    }

    public void deleteOverlay(Long id) {
        repo.deleteById(id);
    }

    public void clearOverlays(String symbol, String timeframe) {
        repo.deleteBySymbolAndTimeframe(symbol, timeframe);
    }
}
