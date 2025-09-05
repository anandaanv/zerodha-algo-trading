package com.dtech.drawings.repo;

import com.dtech.drawings.entity.DrawingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DrawingRepository extends JpaRepository<DrawingEntity, Long> {
    List<DrawingEntity> findBySymbolAndTimeframeOrderByUpdatedAtDesc(String symbol, String timeframe);
    List<DrawingEntity> findByUserIdAndSymbolAndTimeframeOrderByUpdatedAtDesc(String userId, String symbol, String timeframe);
}
