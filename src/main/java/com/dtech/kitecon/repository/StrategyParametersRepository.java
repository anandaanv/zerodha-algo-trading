package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.StrategyParameters;
import com.dtech.kitecon.misc.StrategyEnvironment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StrategyParametersRepository extends JpaRepository<StrategyParameters, Integer> {

  List<StrategyParameters> findByStrategyNameAndInstrumentNameAndEnvironment(String strategyName,
      String InstrumentName, StrategyEnvironment env);

}


