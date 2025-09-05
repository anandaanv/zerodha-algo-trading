package com.dtech.algo.service;

import com.dtech.kitecon.repository.IndexSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SymbolExpansionService {

  private final IndexSymbolRepository indexSymbolRepository;

  /**
   * Expand a single input which may be an index into its constituent symbols; falls back to the input if not an index.
   */
  public List<String> expandSymbolOrIndex(String symbolOrIndex) {
    List<String> results = new ArrayList<>();
    try {
      if (symbolOrIndex != null && indexSymbolRepository.existsByIndexName(symbolOrIndex)) {
        results.addAll(indexSymbolRepository.findAllSymbolsByIndexName(symbolOrIndex));
      } else {
        results.add(symbolOrIndex);
      }
    } catch (Exception e) {
      log.warn("Index expansion failed for {}: {}", symbolOrIndex, e.getMessage());
      results.add(symbolOrIndex);
    }
    return results;
  }

  /**
   * Expand a list of symbols/indices into a flat list of symbols.
   */
  public List<String> expandSymbols(Collection<String> inputs) {
    List<String> out = new ArrayList<>();
    if (inputs == null) return out;
    inputs.forEach(s -> out.addAll(expandSymbolOrIndex(s)));
    return out;
  }
}
