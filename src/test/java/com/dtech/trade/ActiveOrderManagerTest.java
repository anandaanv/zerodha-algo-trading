package com.dtech.trade;

import com.dtech.kitecon.market.Provider;
import com.dtech.trade.order.OrderManager;
import com.dtech.trade.repository.OrderRepository;
import com.dtech.trade.zerodha.ZerodhaOrderManager;
import com.zerodhatech.kiteconnect.KiteConnect;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;


@ExtendWith(MockitoExtension.class)
class ActiveOrderManagerTest {

    @Mock
    private KiteConnect kiteConnect;

    @Mock
    private OrderRepository orderRepository;

    @Spy
    private List<OrderManager> orderManagers;

    private ZerodhaOrderManager o = new ZerodhaOrderManager(kiteConnect);
    {
        orderManagers = Collections.singletonList(o);
    }

    @InjectMocks
    private ActiveOrderManager activeOrderManager;

    @Test
    void initialize() {
        activeOrderManager.initialize();
        Assertions.assertThat(activeOrderManager.orderManagers).contains(Map.entry(Provider.ZERODHA, o));
    }
}