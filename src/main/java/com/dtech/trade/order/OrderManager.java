package com.dtech.trade.order;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.Provider;
import com.dtech.kitecon.market.orders.OrderException;
import com.dtech.trade.model.Order;

public interface OrderManager {

    public Provider getProvider();

    RealTradeOrder placeIntradayLimitsOrder(RealTradeOrder order)
            throws OrderException;

}
