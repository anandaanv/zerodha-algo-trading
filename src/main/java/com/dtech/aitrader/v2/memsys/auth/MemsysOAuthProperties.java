package com.dtech.aitrader.v2.memsys.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * memsys OAuth infrastructure config. Populated from application.properties or env.
 *
 * <p>Per-user DCR client credentials (client_id + client_secret) are stored per-user
 * on the {@code user_memsys_config} row, not system-wide. Only infrastructure URLs
 * (authorize, token, DCR endpoints, redirect URI, and scopes) come from properties.
 */
@Configuration
@ConfigurationProperties(prefix = "memsys.oauth")
@Data
public class MemsysOAuthProperties {

    /** Cognito authorize URL (login). Default: https://memauth.dheemantech.in/login */
    private String authorizeUrl = "https://memauth.dheemantech.in/login";

    /** Cognito token endpoint. Default: https://memauth.dheemantech.in/oauth2/token */
    private String tokenUrl = "https://memauth.dheemantech.in/oauth2/token";

    /** memsys DCR endpoint for one-time client registration. */
    private String dcrUrl = "https://memsys.dheemantech.in/oauth/register";

    /** Redirect URI registered with the DCR client. Must match exactly. */
    private String redirectUri = "https://tradeapi.dheemantech.in/memsys/callback";

    /**
     * Scopes requested at authorize-time. Cognito custom scopes are URI-prefixed —
     * "https://memsys.dheemantech.in/memory.read https://memsys.dheemantech.in/memory.write".
     */
    private String scopes = "https://memsys.dheemantech.in/memory.read https://memsys.dheemantech.in/memory.write";
}
