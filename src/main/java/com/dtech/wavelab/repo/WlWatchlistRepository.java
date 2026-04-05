package com.dtech.wavelab.repo;

import com.dtech.wavelab.entity.WlWatchlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WlWatchlistRepository extends JpaRepository<WlWatchlist, Long> {
    List<WlWatchlist> findAllByOrderByCreatedAtDesc();
}
