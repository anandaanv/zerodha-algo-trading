package com.dtech.trade;

import com.dtech.kitecon.market.Provider;
import com.dtech.trade.model.Order;
import com.dtech.trade.order.OrderManager;
import com.dtech.trade.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class ActiveOrderManager {
    protected final OrderRepository repository;
    protected final List<OrderManager> orderManagerList;

    protected Map<Provider, OrderManager> orderManagers = new HashMap<>();

    @PostConstruct
    public void initialize() {
        orderManagerList.forEach(manager -> orderManagers.put(manager.getProvider(), manager));
    }

    public void placeOrder(Order order, Provider provider) {

    }
}
