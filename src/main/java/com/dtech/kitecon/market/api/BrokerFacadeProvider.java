package com.dtech.kitecon.market.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Registry of BrokerFacade implementations.
 * Provides the default broker based on configuration.
 */
@Component
public class BrokerFacadeProvider {

    private final Map<BrokerType, BrokerFacade> facades = new EnumMap<>(BrokerType.class);

    @Value("${broker.default:ZERODHA}")
    private String defaultBroker;

    public BrokerFacadeProvider(List<BrokerFacade> brokerFacades) {
        for (BrokerFacade facade : brokerFacades) {
            facades.put(facade.getType(), facade);
        }
    }

    public BrokerFacade get(BrokerType type) {
        BrokerFacade facade = facades.get(type);
        if (facade == null) throw new IllegalArgumentException("No broker registered for: " + type);
        return facade;
    }

    public BrokerFacade getDefault() {
        return get(BrokerType.valueOf(defaultBroker));
    }

    public Collection<BrokerFacade> getAll() {
        return Collections.unmodifiableCollection(facades.values());
    }
}
