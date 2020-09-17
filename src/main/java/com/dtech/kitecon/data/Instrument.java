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
  private Long instrument_token;
  @Column
  private Long exchange_token;
  @Column
  private String tradingsymbol;
  @Column
  private String name;
  @Column
  private Double last_price;
  @Column
  private LocalDateTime expiry;
  @Column
  private String strike;
  @Column
  private Double tick_size;
  @Column
  private Integer lot_size;
  @Column
  private String instrument_type;
  @Column
  private String segment;
  @Column
  private String exchange;
}
