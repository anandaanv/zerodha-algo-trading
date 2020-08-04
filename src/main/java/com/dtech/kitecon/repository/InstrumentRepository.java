package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    List<Instrument> findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(String symbol, Date expiryStart, Date expiryEnd, String[] exchanges);
    List<Instrument> findAllByTradingsymbolStartingWithAndExpiryIsNullAndExchangeIn(String symbol, String[] exchanges);
    List<Instrument> findAllByTradingsymbolStartingWithAndExchangeIn(String symbol, String[] exchanges);
    Instrument findByTradingsymbolAndExchangeIn(String symbol, String[] exchanges);

}
