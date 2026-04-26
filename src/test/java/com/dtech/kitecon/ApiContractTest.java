package com.dtech.kitecon;

import com.dtech.kitecon.auth.AuthService;
import com.dtech.kitecon.auth.JwtUtil;
import com.dtech.kitecon.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.HttpMethod;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.equalTo;

/**
 * API Contract Test: Verifies all public REST endpoints exist and are not returning 404.
 * Runs with Spring Boot test context and a test-profile database.
 * Tagged as "integration" to exclude from normal test runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@ActiveProfiles("test")
class ApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    private String authToken;

    @BeforeEach
    void setUp() throws Exception {
        // Create a test user and generate a token
        User testUser = new User();
        testUser.setUsername("testuser");
        testUser.setRole(User.Role.ADMIN);
        authToken = jwtUtil.generateToken("testuser", User.Role.ADMIN);
    }

    /**
     * Test GET endpoints that should exist and not return 404.
     * These tests use auth token for protected endpoints.
     */
    @ParameterizedTest(name = "GET {0} should exist")
    @CsvSource({
            // Auth endpoints (unprotected)
            "/api/auth/me",

            // Drawing endpoints
            "/api/drawings",

            // Snapshot endpoints
            "/api/snapshots/my-snapshots",
            "/api/snapshots/public",
            "/api/snapshots/trending",
            "/api/snapshots/summary",
            "/api/snapshots/stats",

            // Hypothesis endpoints
            "/api/hypotheses/board",

            // Analysis endpoints
            "/api/analysis/observations",
            "/api/analysis/fundamentals",
            "/api/analysis/news",
            "/api/analysis/correlation",
            "/api/analysis/social",

            // Copilot endpoints
            "/api/copilot/orchestrator",
            "/api/copilot/orchestrator/default",
            "/api/copilot/credentials/status",
            "/api/copilot/skills",

            // Trade endpoints
            "/api/trades",
            "/api/trades/dashboard",
            "/api/trades/overrides",

            // Monitor endpoints
            "/api/monitor/alerts",

            // Chart state endpoints
            "/api/chart-state/drawings",

            // Chart pattern endpoints
            "/api/chartpattern/zigzag/plot",
            "/api/chartpattern/zigzag/market-structure",
            "/api/chartpattern/zigzag/incremental",
            "/api/chartpattern/zigzag/impulse-labels",

            // Screener endpoints
            "/api/screener-meta/series-enums",
            "/api/screeners",

            // Trade signal endpoints
            "/api/trade-signals",

            // Trade order endpoints
            "/api/trade-orders",
            "/api/trade-orders/summary",

            // Debug endpoints
            "/api/debug/elliott",

            // Scan endpoints
            "/api/scan",
            "/api/scan/groups",

            // Segment config endpoints
            "/api/segment-config",

            // Charts endpoints
            "/api/charts/tradingview/multipanel",

            // Intervals endpoints
            "/api/intervals/mapping",
            "/api/intervals/periods",

            // Kite endpoints
            "/api/admin/kite-configs",

            // Dhan endpoints
            "/api/dhan/status",
            "/api/dhan/test",

            // OHLC endpoint
            "/api/ohlc",

            // Watchlist endpoints
            "/api/watchlists",

            // Subscriptions endpoint
            "/api/subscriptions",

            // Tags endpoint
            "/api/tags",

            // Layouts endpoints
            "/api/layouts",

            // Pattern scan endpoints
            "/api/pattern-scan",

            // Pattern screener endpoints
            "/api/pattern-screener",

            // Remote sync endpoints
            "/api/remote-sync",

            // Settings endpoints
            "/api/settings/ai-providers"
    })
    void testGetEndpointExists(String path) throws Exception {
        mockMvc.perform(get(path)
                .header("Authorization", "Bearer " + authToken))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // Accept any response except 404
                    if (status == 404) {
                        throw new AssertionError("Endpoint " + path + " returned 404");
                    }
                });
    }

    /**
     * Test POST endpoints that should exist and not return 404.
     * These tests use auth token for protected endpoints.
     */
    @ParameterizedTest(name = "POST {0} should exist")
    @CsvSource({
            // Auth endpoints (unprotected)
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/google",

            // Drawing endpoints
            "/api/drawings",

            // Snapshot draft endpoints
            "/api/snapshots/drafts",
            "/api/snapshots/create",
            "/api/snapshots/validate",

            // Hypothesis endpoints
            "/api/hypotheses/{id}/confirm",
            "/api/hypotheses/{id}/dismiss",
            "/api/hypotheses/flags/{flagId}/acknowledge",

            // Analysis endpoints
            "/api/analysis/trigger",
            "/api/analysis/respond",
            "/api/analysis/confirm-wave",
            "/api/analysis/full-elliott",
            "/api/analysis/full-elliott-verified",
            "/api/analysis/analyze",
            "/api/analysis/technical-chat",
            "/api/analysis/multi-chart-chat",
            "/api/analysis/process",

            // Copilot endpoints
            "/api/copilot/orchestrator/validate",
            "/api/copilot/orchestrator/test",
            "/api/copilot/credentials",
            "/api/copilot/skills",
            "/api/copilot/skills/seed",
            "/api/copilot/skills/ai-assist",

            // Trade endpoints
            "/api/trades/{id}/open",
            "/api/trades/{id}/close",
            "/api/trades/{id}/override",

            // Monitor endpoints
            "/api/monitor/candle-close",
            "/api/monitor/alerts/{id}/acknowledge",

            // Chart state endpoints
            "/api/chart-state/drawings",

            // Chart pattern endpoints
            "/api/chartpattern/zigzag/stock",
            "/api/chartpattern/zigzag/index",

            // Screeners endpoints
            "/api/screeners",
            "/api/screeners/{id}/run",
            "/api/screeners/{id}/subscribe",
            "/api/screeners/validate",

            // Trade signals endpoints
            "/api/trade-signals/expire-stale",

            // Scanner endpoint
            "/api/scanner/scan-now",

            // Segment config endpoints
            "/api/segment-config",
            "/api/segment-config/seed",

            // Charts endpoints
            "/api/charts/v1/analyze",
            "/api/charts/tradingview",
            "/api/charts/v1/screen",

            // Kite endpoints
            "/api/admin/kite-configs",
            "/api/admin/kite-configs/{id}/disconnect",
            "/api/admin/kite-configs/{id}/process-token",
            "/api/admin/eod-sync",

            // Dhan endpoints
            "/api/dhan/update-token",

            // Watchlist endpoints
            "/api/watchlists",
            "/api/watchlists/{id}/symbols",

            // Subscription updater endpoint
            "/api/subscription-updater",

            // Layouts endpoints
            "/api/layouts",

            // Pattern scan endpoints
            "/api/pattern-scan",

            // Pattern screener endpoints
            "/api/pattern-screener/{id}/symbols",

            // Remote sync endpoints
            "/api/remote-sync",

            // Settings endpoints
            "/api/settings/ai-providers",

            // Simulation endpoint
            "/api/simulation/backtest"
    })
    void testPostEndpointExists(String path) throws Exception {
        mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + authToken)
                .contentType("application/json")
                .content("{}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // Accept any response except 404
                    // Some endpoints may return 400/422 for missing/invalid body
                    if (status == 404) {
                        throw new AssertionError("Endpoint " + path + " returned 404");
                    }
                });
    }

    /**
     * Test PUT endpoints that should exist and not return 404.
     * These tests use auth token for protected endpoints.
     */
    @ParameterizedTest(name = "PUT {0} should exist")
    @CsvSource({
            // Drawing endpoints
            "/api/drawings/{id}",

            // Snapshot draft endpoints
            "/api/snapshots/drafts/{id}",

            // Snapshot endpoints
            "/api/snapshots/{id}/visibility",

            // Copilot orchestrator endpoint
            "/api/copilot/orchestrator",

            // Copilot skills endpoints
            "/api/copilot/skills/{id}",

            // Kite endpoints
            "/api/admin/kite-configs/{id}",

            // Screeners endpoint
            "/api/screeners/{id}",

            // Watchlist endpoints
            "/api/watchlists/{id}",

            // Layouts endpoints
            "/api/layouts/{id}",

            // Segment config endpoint
            "/api/segment-config/{id}"
    })
    void testPutEndpointExists(String path) throws Exception {
        mockMvc.perform(put(path)
                .header("Authorization", "Bearer " + authToken)
                .contentType("application/json")
                .content("{}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // Accept any response except 404
                    if (status == 404) {
                        throw new AssertionError("Endpoint " + path + " returned 404");
                    }
                });
    }

    /**
     * Test DELETE endpoints that should exist and not return 404.
     * These tests use auth token for protected endpoints.
     */
    @ParameterizedTest(name = "DELETE {0} should exist")
    @CsvSource({
            // Drawing endpoints
            "/api/drawings/{id}",

            // Snapshot draft endpoints
            "/api/snapshots/drafts/{id}",

            // Snapshot endpoints
            "/api/snapshots/{id}",

            // Copilot orchestrator endpoint
            "/api/copilot/orchestrator",

            // Copilot credentials endpoint
            "/api/copilot/credentials",

            // Copilot skills endpoints
            "/api/copilot/skills/{id}",

            // Chart state endpoint
            "/api/chart-state/drawings",

            // Analysis endpoint
            "/api/analysis/cache",

            // Kite endpoint
            "/api/admin/kite-configs/{id}",

            // Watchlist endpoints
            "/api/watchlists/{id}",
            "/api/watchlists/{id}/symbols/{symbol}",

            // Scan endpoints
            "/api/scan/groups/{groupId}/members/{userId}",

            // Segment config endpoints
            "/api/segment-config/{id}",
            "/api/segment-config/all",

            // Layouts endpoints
            "/api/layouts/{id}",

            // Pattern scan endpoint
            "/api/pattern-scan/{id}"
    })
    void testDeleteEndpointExists(String path) throws Exception {
        mockMvc.perform(delete(path)
                .header("Authorization", "Bearer " + authToken))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // Accept any response except 404
                    if (status == 404) {
                        throw new AssertionError("Endpoint " + path + " returned 404");
                    }
                });
    }

    /**
     * Test auth endpoints without token (they should be accessible)
     */
    @ParameterizedTest(name = "Auth endpoint {0} should be accessible without token")
    @CsvSource({
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/google"
    })
    void testAuthEndpointsWithoutToken(String path) throws Exception {
        mockMvc.perform(post(path)
                .contentType("application/json")
                .content("{}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // Accept any response except 404
                    if (status == 404) {
                        throw new AssertionError("Auth endpoint " + path + " returned 404");
                    }
                });
    }
}
