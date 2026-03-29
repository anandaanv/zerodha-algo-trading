package com.dtech.kitecon.screener.elliott.repository;

import com.dtech.kitecon.screener.elliott.entity.ElliottScreener;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ElliottScreenerRepository extends JpaRepository<ElliottScreener, Long> {
    List<ElliottScreener> findByEnabledTrue();
    List<ElliottScreener> findByUserId(Long userId);
    Optional<ElliottScreener> findByUserIdAndName(Long userId, String name);
    Optional<ElliottScreener> findByIdAndUserId(Long id, Long userId);
}
