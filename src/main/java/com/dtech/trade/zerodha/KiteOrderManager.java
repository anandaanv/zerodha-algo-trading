package com.dtech.trade.zerodha;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.market.Provider;
import com.dtech.kitecon.market.orders.OrderException;
import com.dtech.trade.order.OrderManager;
import com.dtech.trade.order.RealTradeOrder;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
@Primary
public class KiteOrderManager implements OrderManager {

    private final KiteConnectConfig connectConfig;

    @Override
    public Provider getProvider() {
        return Provider.ZERODHA;
    }

    @Override
    public RealTradeOrder placeIntradayLimitsOrder(RealTradeOrder order) throws OrderException {
        OrderParams params = new OrderParams();
        params.exchange = "NSE";
        params.tradingsymbol = order.getInstrument().getTradingsymbol();
        params.transactionType = order.getOrderType().toUpperCase();
        params.quantity = order.getQuantity();
        params.price = order.getPrice();
        params.product = "MIS";
        params.orderType = "LIMIT";
        params.validity = "DAY";
        params.disclosedQuantity = order.getDisclosedQuantity();
        params.parentOrderId = order.getParentOrderId();
        try {
            OrderResponse response = connectConfig.getKiteConnect().placeOrder(params, "regular");
            // kiteconnect 4.0.0 returns OrderResponse with orderId; fetch full order details
            java.util.List<Order> orderHistory = connectConfig.getKiteConnect().getOrderHistory(response.orderId);
            if (orderHistory != null && !orderHistory.isEmpty()) {
                return new ZerodhaOrder(orderHistory.get(0), order.getInstrument());
            }
            // Fallback: create a minimal Order with just the orderId if order history is unavailable
            Order placedOrder = new Order();
            placedOrder.orderId = response.orderId;
            return new ZerodhaOrder(placedOrder, order.getInstrument());
        } catch (Throwable e) {
            throw new OrderException(e);
        }
    }
}
