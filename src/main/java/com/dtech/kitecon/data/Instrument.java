package com.dtech.kitecon.data;

import java.time.LocalDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Instrument {

  @Id
  private Long instrumentToken;
  @Column
  private Long exchangeToken;
  @Column
  private String tradingsymbol;
  @Column
  private String name;
  @Column
  private Double lastPrice;
  @Column
  private LocalDateTime expiry;
  @Column
  private String strike;
  @Column
  private Double tickSize;
  @Column
  private Integer lotSize;
  @Column
  private String instrumentType;
  @Column
  private String segment;
  @Column
  private String exchange;
}
