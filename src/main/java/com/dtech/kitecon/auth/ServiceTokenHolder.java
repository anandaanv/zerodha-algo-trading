package com.dtech.kitecon.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Holder for service token that can be injected into services
 * for backend-to-backend API calls.
 * Tokens are fetched from database and rotate daily.
 */
@Component
@RequiredArgsConstructor
public class ServiceTokenHolder {

    private final ServiceTokenService serviceTokenService;

    /**
     * Get the current valid service token from database.
     * @return Current service token
     */
    public String getServiceToken() {
        return serviceTokenService.getCurrentToken();
    }

    /**
     * Returns the service token as a query parameter string
     * @return Query parameter string like "?servicetoken=xxx" or "&servicetoken=xxx"
     */
    public String asQueryParam(boolean firstParam) {
        return (firstParam ? "?" : "&") + "servicetoken=" + getServiceToken();
    }

    /**
     * Returns the service token as a query parameter (assumes it's not the first param)
     * @return Query parameter string like "&servicetoken=xxx"
     */
    public String asQueryParam() {
        return asQueryParam(false);
    }
}
