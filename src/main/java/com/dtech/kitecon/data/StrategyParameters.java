package com.dtech.kitecon.data;

import com.dtech.kitecon.misc.StrategyEnvironment;
import com.dtech.kitecon.strategy.TradeDirection;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
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
