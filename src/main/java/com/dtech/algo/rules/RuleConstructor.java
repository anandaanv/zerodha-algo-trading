package com.dtech.algo.rules;

import com.dtech.algo.registry.common.ConstructorArgs;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@Data
@ToString
public class RuleConstructor {

  private List<ConstructorArgs> args;

}
