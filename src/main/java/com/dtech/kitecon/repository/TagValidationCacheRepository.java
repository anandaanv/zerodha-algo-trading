package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.TagValidationCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TagValidationCacheRepository extends JpaRepository<TagValidationCache, Long> {

    /**
     * Find cached validation by original tag (case-insensitive)
     */
    Optional<TagValidationCache> findByOriginalTagIgnoreCase(String originalTag);

    /**
     * Find all tags that normalize to the same value
     */
    List<TagValidationCache> findByNormalizedTag(String normalizedTag);

    /**
     * Increment validation count for a tag
     */
    @Modifying
    @Query("UPDATE TagValidationCache t SET t.validationCount = t.validationCount + 1, t.lastUsedAt = CURRENT_TIMESTAMP WHERE t.originalTag = :originalTag")
    void incrementValidationCount(String originalTag);

    /**
     * Get most frequently used tags
     */
    List<TagValidationCache> findTop50ByOrderByValidationCountDesc();
}
