package com.dtech.kitecon.data;

import lombok.Data;

import javax.persistence.MappedSuperclass;
import java.util.Date;

@MappedSuperclass
@Data
public class BaseCandle {
  private long id;
  private Double open;
  private Double high;
  private Double low;
  private Double close;
  private Double volume;
  private Double oi;
  private Date timestamp;
  private Instrument instrument;
}
