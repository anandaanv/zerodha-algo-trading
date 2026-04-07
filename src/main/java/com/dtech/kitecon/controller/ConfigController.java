package com.dtech.kitecon.controller;

import com.dtech.algo.runner.candle.KiteTickerService;
import com.dtech.kitecon.config.KiteConnectPool;
import com.dtech.kitecon.service.IndexSymbolUpdaterService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.io.IOException;

/**
 * Kite OAuth login and callback controller.
 *
 * Login flow for N apps (chained redirects):
 *
 *   GET /kite-login
 *       → Redirects to App 0's Kite OAuth page
 *       → App 0 callback URL in Kite console must be: {server}/app/0
 *
 *   GET /app/0?request_token=XXX
 *       → Exchanges token for App 0
 *       → If N > 1: redirects to App 1's Kite OAuth page
 *       → If N == 1: redirects to /dashboard
 *
 *   GET /app/N?request_token=XXX
 *       → Exchanges token for App N
 *       → If more apps remain: redirects to next
 *       → Otherwise: redirects to /dashboard ✓
 *
 * From Day 2: Kite session cookie is shared across all apps (same kite.zerodha.com domain),
 * so each redirect completes instantly without re-entering credentials.
 *
 * Backward compat: GET /app?request_token=XXX → treated as app index 0.
 */
@RestController
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@CrossOrigin(origins = {"*"})
@Slf4j
public class ConfigController {

    private final KiteConnectPool kiteConnectPool;
    private final IndexSymbolUpdaterService updaterService;
    private final KiteTickerService tickerService;

    @Value("${app.frontend.url:}")
    private String frontendUrl;

    /** Step 1: Start the OAuth chain — redirect to App 0's login page */
    @GetMapping("/kite-login")
    public RedirectView startKiteLogin() {
        String url = kiteConnectPool.getLoginUrl(0);
        if (url == null) {
            throw new IllegalStateException("Kite app 0 is not configured. Check credentials in app_secrets or kite_connect_settings.");
        }
        log.info("Starting Kite OAuth chain — redirecting to app 0 login");
        return new RedirectView(url);
    }

    /**
     * OAuth callback for app at given index.
     * Exchanges the request_token, then redirects to next app or dashboard.
     */
    @GetMapping("/app/{appIndex}")
    public RedirectView kiteCallback(
            @PathVariable int appIndex,
            @RequestParam("request_token") String token
    ) throws IOException, KiteException {
        log.info("Kite OAuth callback for app index {}", appIndex);
        kiteConnectPool.initializeApp(appIndex, token);

        // Reinitialise the ticker with refreshed credentials
        tickerService.init();

        int nextIndex = appIndex + 1;
        if (nextIndex < kiteConnectPool.getAppCount()) {
            String nextUrl = kiteConnectPool.getLoginUrl(nextIndex);
            log.info("App {} done — redirecting to app {} login", appIndex, nextIndex);
            return new RedirectView(nextUrl);
        } else {
            log.info("All {} Kite app(s) authenticated — redirecting to dashboard", kiteConnectPool.getAppCount());
            return new RedirectView(frontendUrl + "/dashboard");
        }
    }

    /**
     * Backward-compatible callback: /app?request_token=XXX → treated as app 0.
     * Useful if the Kite console still has the old redirect URI configured.
     */
    @GetMapping("/app")
    public RedirectView kiteCallbackLegacy(@RequestParam("request_token") String token)
            throws IOException, KiteException {
        return kiteCallback(0, token);
    }

    /** Re-authenticate without starting a new session (e.g. after token expiry). */
    @GetMapping("/update-token")
    public RedirectView updateToken(@RequestParam("request_token") String token)
            throws IOException, KiteException {
        return kiteCallbackLegacy(token);
    }
}
