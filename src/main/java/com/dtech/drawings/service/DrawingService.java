package com.dtech.drawings.service;

import com.dtech.drawings.entity.DrawingEntity;
import com.dtech.drawings.model.DrawingType;
import com.dtech.drawings.repo.DrawingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DrawingService {

    private final DrawingRepository repo;

    @Transactional(readOnly = true)
    public List<DrawingEntity> list(String userId, String symbol, String timeframe) {
        if (userId != null && !userId.isBlank()) {
            return repo.findByUserIdAndSymbolAndTimeframeOrderByUpdatedAtDesc(userId, symbol, timeframe);
        }
        return repo.findBySymbolAndTimeframeOrderByUpdatedAtDesc(symbol, timeframe);
    }

    @Transactional
    public DrawingEntity create(String userId, String symbol, String timeframe, DrawingType type, String name, String payloadJson) {
        DrawingEntity e = DrawingEntity.builder()
                .userId(userId)
                .symbol(symbol)
                .timeframe(timeframe)
                .type(type)
                .name(name)
                .payloadJson(payloadJson)
                .build();
        return repo.save(e);
    }

    @Transactional
    public Optional<DrawingEntity> update(Long id, String name, String payloadJson) {
        return repo.findById(id).map(e -> {
            if (name != null) e.setName(name);
            if (payloadJson != null) e.setPayloadJson(payloadJson);
            return repo.save(e);
        });
    }

    @Transactional
    public void delete(Long id) {
        repo.deleteById(id);
    }
}
