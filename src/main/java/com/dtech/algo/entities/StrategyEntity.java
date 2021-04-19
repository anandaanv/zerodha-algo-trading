package com.dtech.algo.entities;

import com.dtech.kitecon.data.Instrument;

import javax.persistence.*;

@Entity(name = "strategy")
public class StrategyEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @Column
    private String criteria;

    @Column
    private String barSeriesConfig;

    @Column
    private String order;

    @JoinColumn
    private String barSeriesName;

    @Column
    private Boolean enabled;

}
