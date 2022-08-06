package com.dtech.trade.zerodha;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.Provider;
import com.dtech.kitecon.market.orders.OrderException;
import com.dtech.trade.order.RealTradeOrder;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class ZerodhaOrderManagerTest {

    @Mock
    private KiteConnect kiteConnect;

    @InjectMocks
    private ZerodhaOrderManager zerodhaOrderManager;

    @Test
    void placeIntradayLimitsOrder() throws IOException, KiteException, OrderException {
        Instrument instrument = new Instrument();
        instrument.setTradingsymbol("SBIN");
        instrument.setExchange("NSE");
        String orderType = "BUY";
        Integer quantity = 10;
        double price = 100.05;
        Integer disclosedQuantity = 10;
        String parentOrderId = UUID.randomUUID().toString();

        com.dtech.trade.model.Order orderToPlace = new com.dtech.trade.model.Order();
        orderToPlace.setQuantity(quantity);
        orderToPlace.setPrice(price);
        orderToPlace.setInstrument(instrument);
        orderToPlace.setOrderType(orderType);
        orderToPlace.setParentOrderId(parentOrderId);
        orderToPlace.setDisclosedQuantity(quantity);

        OrderParams params = new OrderParams();
        params.exchange = "NSE";
        params.tradingsymbol = instrument.getTradingsymbol();
        params.transactionType = orderType.toUpperCase();
        params.quantity = quantity;
        params.price = price;
        params.product = "MIS";
        params.orderType = "LIMIT";
        params.validity = "DAY";
        params.disclosedQuantity = disclosedQuantity;
        params.parentOrderId = parentOrderId;
        Order kiteOrder = new Order();
        kiteOrder.exchangeOrderId = "001";
        Mockito.when(kiteConnect.placeOrder(argThat(argument -> {
            return argument.parentOrderId.equals(parentOrderId) &&
                    argument.orderType.equals("LIMIT") &&
                    argument.product.equals("MIS") &&
                    argument.quantity.equals(quantity) &&
                    argument.price.equals(price) &&
                    argument.exchange.equals(instrument.getExchange()) &&
                    argument.disclosedQuantity.equals(quantity);
        }), eq("regular"))).thenReturn(kiteOrder);
        RealTradeOrder realTradeOrder = zerodhaOrderManager.placeIntradayLimitsOrder(orderToPlace);
        assertEquals(realTradeOrder.getExchangeOrderId(), "001");
    }

    @Test
    void getProvider() {
        assertEquals(zerodhaOrderManager.getProvider(), Provider.ZERODHA);
    }
}