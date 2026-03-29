package com.dtech.kitecon.screener.elliott.repository;

import com.dtech.kitecon.screener.elliott.entity.SuggestionChartLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface SuggestionChartLayoutRepository extends JpaRepository<SuggestionChartLayout, Long> {
    List<SuggestionChartLayout> findBySuggestionIdOrderByTabOrder(Long suggestionId);

    @Modifying
    @Transactional
    void deleteBySuggestionId(Long suggestionId);
}
