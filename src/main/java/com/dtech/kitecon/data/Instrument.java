package com.dtech.kitecon.data;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import lombok.*;

@Entity
@Data
@Builder
@AllArgsConstructor
@EqualsAndHashCode
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
