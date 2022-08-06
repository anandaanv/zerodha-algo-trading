package com.dtech.trade.zerodha;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.market.Provider;
import com.dtech.kitecon.market.orders.OrderException;
import com.dtech.trade.order.OrderManager;
import com.dtech.trade.order.RealTradeOrder;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
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
            Order exchangeOrder = connectConfig.getKiteConnect().placeOrder(params, "regular");
            return new ZerodhaOrder(exchangeOrder, order.getInstrument());
        } catch (Throwable e) {
            throw new OrderException(e);
        }
    }
}
