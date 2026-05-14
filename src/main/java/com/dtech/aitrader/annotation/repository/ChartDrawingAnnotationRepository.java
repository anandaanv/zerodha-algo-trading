package com.dtech.aitrader.annotation.repository;

import com.dtech.aitrader.annotation.entity.ChartDrawingAnnotation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChartDrawingAnnotationRepository extends JpaRepository<ChartDrawingAnnotation, Long> {

    List<ChartDrawingAnnotation> findByUserIdAndTabUuidAndSymbolAndActiveTrueOrderByWeightDescUpdatedAtDesc(
            Long userId, String tabUuid, String symbol);

    List<ChartDrawingAnnotation> findByUserIdAndSymbolAndActiveTrueOrderByWeightDescUpdatedAtDesc(
            Long userId, String symbol);

    Optional<ChartDrawingAnnotation> findByUserIdAndTabUuidAndSymbolAndDrawingId(
            Long userId, String tabUuid, String symbol, String drawingId);
}
