package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.InstrumentLtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InstrumentLtpRepository extends JpaRepository<InstrumentLtp, String> {

    Optional<InstrumentLtp> findByTradingSymbol(String tradingSymbol);
}
