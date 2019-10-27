package com.dtech.kitecon.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.MappedSuperclass;
import javax.persistence.OneToOne;
import javax.persistence.UniqueConstraint;
import java.util.Date;

@MappedSuperclass
@Data
@NoArgsConstructor
@AllArgsConstructor(onConstructor = @__(@Builder))
public class BaseCandle {
  @Column
  protected Double open;
  @Column
  protected Double high;
  @Column
  protected Double low;
  @Column
  protected Double close;
  @Column
  protected Long volume;
  @Column
  protected Long oi;
  @Column
  protected Date timestamp;
  @OneToOne
  protected Instrument instrument;
}
