package com.dtech.trade.model;

import com.dtech.kitecon.data.Instrument;
import com.dtech.trade.order.RealTradeOrder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import jakarta.persistence.*;
import org.hibernate.usertype.UserType;

import java.time.ZonedDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity(name = "trade_order")
public class Order implements RealTradeOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String parentOrderId;

    @Column
    private String exchangeOrderId;

    @Column
    private String status;

    @Column
    private String statusMessage;

    @Column
    private double averagePrice;

    @Column
    private  Integer fulfilledQuantity;

    @Column
    private  ZonedDateTime timestamp;

    @Column
    private Integer disclosedQuantity;
    @Column
    private String validity;

    @ManyToOne
    private Instrument instrument;

    @Column
    private String orderVariety;
    @Column
    private String userId;
    @Column
    private String orderType;
    @Column
    private double triggerPrice;
    @Column
    private Double price;
    @Column
    private String product;
    @Column
    private String accountId;
    @Column
    private String exchange;
    @Column
    private String orderId;
    @Column
    private String symbol;
    @Column
    private Integer pendingQuantity;
    @Column
    private ZonedDateTime orderTimestamp;
    @Column
    private ZonedDateTime exchangeTimestamp;
    @Column
    private ZonedDateTime exchangeUpdateTimestamp;

    @Column
    private Integer quantity;
    @Column
    private String transactionType;

    @Column
    @Enumerated(EnumType.STRING)
    private OrderStatus orderStatus;

}

