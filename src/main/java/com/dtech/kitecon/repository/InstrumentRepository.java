package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.Instrument;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

  List<Instrument> findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(String symbol,
      LocalDateTime expiryStart, LocalDateTime expiryEnd, String[] exchanges);

  List<Instrument> findAllByTradingsymbolStartingWithAndExpiryIsNullAndExchangeIn(String symbol,
      String[] exchanges);

  List<Instrument> findAllByTradingsymbolStartingWithAndExchangeIn(String symbol,
      String[] exchanges);

  Instrument findByTradingsymbolAndExchangeIn(String symbol, String[] exchanges);

}
