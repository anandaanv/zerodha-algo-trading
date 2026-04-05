package com.dtech.wavelab.elliott.repo;

import com.dtech.wavelab.elliott.entity.WleTriangleRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WleTriangleRunRepository extends JpaRepository<WleTriangleRun, Long> {
    Optional<WleTriangleRun> findByIdAndUserId(Long id, Long userId);
}
