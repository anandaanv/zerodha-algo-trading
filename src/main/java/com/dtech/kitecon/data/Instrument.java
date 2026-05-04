package com.dtech.kitecon.data;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.*;

@Entity
@Table(indexes = {
    @Index(name = "idx_tradingsymbol", columnList = "tradingsymbol"),
    @Index(name = "idx_tradingsymbol_exchange", columnList = "tradingsymbol,exchange"),
    @Index(name = "idx_tradingsymbol_expiry", columnList = "tradingsymbol,expiry")
})
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
