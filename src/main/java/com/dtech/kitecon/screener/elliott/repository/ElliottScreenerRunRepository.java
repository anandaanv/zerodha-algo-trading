package com.dtech.kitecon.screener.elliott.repository;

import com.dtech.kitecon.screener.elliott.entity.ElliottScreenerRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ElliottScreenerRunRepository extends JpaRepository<ElliottScreenerRun, Long> {
    List<ElliottScreenerRun> findTop10ByScreenerIdOrderByStartedAtDesc(Long screenerId);
    List<ElliottScreenerRun> findByScreenerIdOrderByStartedAtDesc(Long screenerId);
    Optional<ElliottScreenerRun> findFirstByScreenerIdAndStatusOrderByStartedAtDesc(Long screenerId, String status);
}
