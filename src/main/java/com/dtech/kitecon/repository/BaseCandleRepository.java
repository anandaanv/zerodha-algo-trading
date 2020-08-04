package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;

@NoRepositoryBean
public interface BaseCandleRepository<T extends BaseCandle, ID> extends JpaRepository<T, ID> {
    List<T> findAllByInstrument(Instrument instrument);
    T findFirstByInstrumentOrderByTimestampDesc(Instrument instrument);
    T findFirstByInstrumentOrderByTimestamp(Instrument instrument);
    void deleteByInstrument(Instrument instrument);
}


