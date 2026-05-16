package com.dtech.aitrader.annotation.repository;

import com.dtech.aitrader.annotation.entity.SymbolJournalNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SymbolJournalNoteRepository extends JpaRepository<SymbolJournalNote, Long> {

    /** Newest-first listing of notes a user has written about a symbol. */
    List<SymbolJournalNote> findByUserIdAndSymbolOrderByNoteDateDescCreatedAtDesc(Long userId, String symbol);
}
