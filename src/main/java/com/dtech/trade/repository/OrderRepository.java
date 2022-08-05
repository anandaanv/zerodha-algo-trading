package com.dtech.trade.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository<Order, String> extends JpaRepository<Order, String> {
}
