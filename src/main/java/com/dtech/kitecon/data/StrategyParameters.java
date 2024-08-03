package com.dtech.kitecon.data;

import com.dtech.kitecon.misc.StrategyEnvironment;
import com.dtech.kitecon.strategy.TradeDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(indexes = @Index(columnList = "strategyName,instrumentName,environment"))
public class StrategyParameters {

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(length = 20)
  private String strategyName;

  @Column(length = 50)
  private String instrumentName;

  @Column
  @Enumerated
  private StrategyEnvironment environment;

  @Column
  private String strategyType;

  @Column(length = 50)
  private String configKey;
  @Column(length = 10)
  private String value;
}
