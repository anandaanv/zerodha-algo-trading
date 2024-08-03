package com.dtech.kitecon.strategy.sets;

import com.dtech.kitecon.strategy.builder.StrategyBuilder;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class StrategySet {
  private final Set<StrategyBuilder> strategyBuilders;
  private Map<String, StrategyBuilder> strategyBuilderMap;

  @PostConstruct
  public void buildStrategyBuilderMap() {
    strategyBuilderMap = strategyBuilders.stream()
        .collect(Collectors.toMap(s -> s.getName(), s -> s));
  }

  public StrategyBuilder getStrategy(String name) {
    return strategyBuilderMap.get(name);
  }

}
