package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.ChartLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChartLayoutRepository extends JpaRepository<ChartLayout, Long> {

    /**
     * Find all layouts for a specific user
     */
    List<ChartLayout> findByUsernameOrderByUpdatedAtDesc(String username);

    /**
     * Find a specific layout by ID and username (for security)
     */
    Optional<ChartLayout> findByIdAndUsername(Long id, String username);

    /**
     * Find layout by name and username
     */
    Optional<ChartLayout> findByNameAndUsername(String name, String username);
}
