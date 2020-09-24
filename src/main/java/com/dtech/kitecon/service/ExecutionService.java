package com.dtech.kitecon.service;

import com.dtech.kitecon.market.orders.OrderManager;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.strategy.builder.StrategyBuilder;
import com.dtech.kitecon.strategy.dataloader.InstrumentDataLoader;
import com.dtech.kitecon.strategy.exec.ProductionHandler;
import com.dtech.kitecon.strategy.exec.ProductionSeriesManager;
import com.dtech.kitecon.strategy.sets.StrategySet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExecutionService {

  private final StrategySet strategySet;
  private final InstrumentRepository instrumentRepository;
  private final InstrumentDataLoader instrumentDataLoader;
  private final OrderManager ordermanager;
  private final ProductionSeriesManager productionSeriesManager;

  private Map<String, ProductionHandler> runners = new HashMap<String, ProductionHandler>();
  private Map<TradeInfo, ProductionHandler> tradeHandlers = new HashMap<TradeInfo, ProductionHandler>();

  public String startStrategy(String strategyName, String instrumentName, String direction) {
    String uuid = UUID.randomUUID().toString();
    StrategyBuilder strategy = strategySet.getStrategy(strategyName);
    TradeInfo key = TradeInfo.builder().direction(direction).symbol(instrumentName).build();
    ProductionHandler handler = tradeHandlers.computeIfAbsent(key,
        tradeInfo -> getProductionHandler(uuid, instrumentName, direction));
    handler.startStrategy(instrumentName, strategy, direction);
    return uuid;
  }

  private ProductionHandler getProductionHandler(String uuid, String instrumentName,
      String direction) {
    ProductionHandler handler = new ProductionHandler(instrumentRepository, instrumentDataLoader,
        ordermanager, productionSeriesManager);
    runners.put(uuid, handler);
    handler.initialise(instrumentName, direction);
    return handler;
  }

}
