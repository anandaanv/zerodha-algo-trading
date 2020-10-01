package com.dtech.algo.indicators;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(exclude = "name")
@Builder
@ToString
public class ConstructorArgs {
  private String type;
  private String name;
  private List<String> values;
}
