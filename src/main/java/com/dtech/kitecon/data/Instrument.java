package com.dtech.kitecon.data;

import lombok.Data;

import javax.persistence.Entity;

@Entity
@Data
public class Instrument {
  private int id;
  private String name;
  private String instrumentId;
}
