package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.IndexSymbol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IndexSymbolRepository extends JpaRepository<IndexSymbol, Long> {

  /**
   * Finds all symbols (exchangeSymbol) for the IndexSymbol entities.
   */
  @Query("SELECT s.exchangeSymbol FROM IndexSymbol s")
  List<String> findAllSymbols();

  /**
   * Finds all symbols (exchangeSymbol) for the given indexName.
   */
  @Query("SELECT s.exchangeSymbol FROM IndexSymbol s WHERE s.indexName = :indexName")
  List<String> findAllSymbolsByIndexName(String indexName);

  /**
   * Checks if a given index name exists.
   */
  boolean existsByIndexName(String indexName);
}
