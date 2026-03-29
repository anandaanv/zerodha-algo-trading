package com.dtech.kitecon.screener.elliott.repository;

import com.dtech.kitecon.screener.elliott.entity.SuggestionChartLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SuggestionChartLayoutRepository extends JpaRepository<SuggestionChartLayout, Long> {
    List<SuggestionChartLayout> findBySuggestionIdOrderByTabOrder(Long suggestionId);
    void deleteBySuggestionId(Long suggestionId);
}
