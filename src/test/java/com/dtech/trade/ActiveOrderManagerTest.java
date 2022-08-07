package com.dtech.trade;

import com.dtech.kitecon.KiteconApplication;
import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.market.Provider;
import com.dtech.trade.model.Order;
import com.dtech.trade.order.OrderManager;
import com.dtech.trade.repository.OrderRepository;
import com.dtech.trade.zerodha.KiteOrderManager;
import com.zerodhatech.kiteconnect.KiteConnect;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Collections;
import java.util.List;
import java.util.Map;


@ExtendWith(MockitoExtension.class)
@SpringBootTest(classes = KiteconApplication.class)
class ActiveOrderManagerTest {

//    @Mock
//    private KiteConnect kiteConnect;
//
//    @Mock
//    private KiteConnectConfig kiteConnectConfig;
//
//    @Mock
//    private OrderRepository orderRepository;
//
//    @Spy
//    private List<OrderManager> orderManagers;
//
//    @Mock
//    private KiteOrderManager kiteOrderManager = new KiteOrderManager(kiteConnectConfig);
//    {
//        orderManagers = Collections.singletonList(kiteOrderManager);
//    }

    @Autowired
    private ActiveOrderManager activeOrderManager;

    @Test
    void initialize() {
        Assertions.assertTrue(activeOrderManager.orderManagers.containsKey(Provider.ZERODHA));
    }

    @Test
    void placeOrder() {
        Order order = new Order();
//        Mockito.when(kiteConnectConfig.getKiteConnect()).thenReturn(kiteConnect);
//        Mockito.when(kiteConnect.getKiteConnect()).thenReturn(kiteConnect);

    }
}