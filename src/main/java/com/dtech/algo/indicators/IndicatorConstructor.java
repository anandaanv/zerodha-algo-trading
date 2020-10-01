package com.dtech.algo.indicators;

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
public class IndicatorConstructor {

  private List<ConstructorArgs> args;

}
