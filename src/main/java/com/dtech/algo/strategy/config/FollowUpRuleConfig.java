package com.dtech.algo.strategy.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;


@Data
@AllArgsConstructor
@Builder
@ToString
public class FollowUpRuleConfig {
  @Default
  private FollowUpRuleType followUpRuleType = FollowUpRuleType.None;
  @Default
  private RuleConfig followUpRule = null;

}
