package com.dtech.kitecon.repository;

import com.dtech.algo.series.InstrumentType;
import com.dtech.kitecon.data.Instrument;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

  List<Instrument> findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(String symbol,
      LocalDateTime expiryStart, LocalDateTime expiryEnd, String[] exchanges);

  List<Instrument> findAllByTradingsymbolStartingWithAndExpiryIsNullAndExchangeIn(String symbol,
      String[] exchanges);

  List<Instrument> findAllByTradingsymbolStartingWithAndExchangeIn(String symbol,
      String[] exchanges);

  List<Instrument> findAllByExchangeAndInstrumentTypeAndTradingsymbolStartingWith(String exchange,
                                                                   String instrumentType, String tradingSymbol);

  Instrument findByTradingsymbolAndExchangeIn(String symbol, String[] exchanges);

  List<Instrument> findAllByTradingsymbolAndExchangeIn(String symbol, String[] exchanges);

  // Simple prefix query without exchange/expiry constraints used by the symbol search API
  List<Instrument> findAllByTradingsymbolStartingWith(String symbol);
  List<Instrument> findAllByTradingsymbol(String symbol);

    Set<Instrument> findAllByTradingsymbolInAndExpiryBefore(List<String> symbols, LocalDateTime expiryBefore);

  List<Instrument> findAllByNameAndInstrumentTypeAndExpiryBetween(String name, String instrumentType,
                                                                   LocalDateTime expiryStart, LocalDateTime expiryEnd);

  @Query("SELECT DISTINCT i.name FROM Instrument i WHERE i.exchange = 'NFO' AND i.instrumentType = 'FUT' ORDER BY i.name")
  List<String> findDistinctFutureUnderlyingNames();

  @Query("SELECT DISTINCT i.name FROM Instrument i WHERE i.exchange = 'NFO' AND i.instrumentType = 'FUT' " +
         "AND EXISTS (SELECT 1 FROM Instrument e WHERE e.tradingsymbol = i.name AND e.exchange = 'NSE') ORDER BY i.name")
  List<String> findDistinctFutureUnderlyingNamesWithNseEquity();
}
