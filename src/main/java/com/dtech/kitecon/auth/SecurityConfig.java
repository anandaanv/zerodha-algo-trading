package com.dtech.kitecon.auth;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.EnumSet;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtRequestFilter jwtRequestFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/", "/index.html", "/assets/**", "/static/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // WebSocket endpoint — JWT auth handled inside STOMP channel interceptor
                        .requestMatchers("/ws/**").permitAll()

                        // Legal pages (required for Google OAuth)
                        .requestMatchers("/privacy-policy", "/privacy-policy.html", "/privacy").permitAll()
                        .requestMatchers("/terms-of-service", "/terms-of-service.html", "/terms").permitAll()

                        // Kite OAuth callbacks — no JWT present (redirected from Kite servers)
                        .requestMatchers("/kite-login", "/app", "/app/**", "/update-token", "/kite-callback/config/**").permitAll()

                        // SPA frontend routes — served as index.html, React Router handles them.
                        // ALL non-API GET paths must be permitAll so the browser can load index.html
                        // even when unauthenticated; the React app itself enforces auth via ProtectedRoute.
                        .requestMatchers(
                                "/login",
                                "/dashboard", "/chart", "/chart-legacy", "/prompt-builder",
                                "/screener", "/screener/**", "/trades", "/trades/**",
                                "/copilot", "/skills", "/kite-success",
                                "/elliott-screener", "/elliott-screener/**",
                                "/scan", "/scan-chart/**",
                                "/admin/kite-config",
                                "/admin/groups",
                                "/elliott-screener/*/status"
                        ).permitAll()

                        // Role-based access control
                        // DELETE operations require MODERATOR/ADMIN
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/chart-state", "/api/layouts/**").hasAnyRole("MODERATOR", "ADMIN")
                        // Indices endpoints
                        .requestMatchers("/api/indices", "/api/indices/**").hasAnyRole("USER", "MODERATOR", "ADMIN")
                        // Chart operations allow USER
                        .requestMatchers("/api/symbols", "/api/ohlc", "/api/chart-state/**", "/api/layouts/**").hasAnyRole("USER", "MODERATOR", "ADMIN")
                        .requestMatchers("/api/drawings/**", "/api/intervals/**").hasAnyRole("USER", "MODERATOR", "ADMIN")
                        .requestMatchers("/api/chart/**", "/api/screener/**", "/api/overlays/**")
                              .hasAnyRole("USER", "MODERATOR", "ADMIN")
                        .requestMatchers("/api/chart-state/export", "/api/chart-state/import", "/api/remote-sync/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Snapshot and analysis endpoints - allow USER role
                        .requestMatchers("/api/snapshots/**", "/api/analysis/**", "/api/tags/**").hasAnyRole("USER", "MODERATOR", "ADMIN")

                        // Co-Pilot endpoints — USER role
                        .requestMatchers(
                                "/api/hypotheses/**", "/api/monitor/**",
                                "/api/copilot/skills/**", "/api/copilot/**",
                                "/api/trades/**"
                        ).hasAnyRole("USER", "MODERATOR", "ADMIN")

                        // Elliott Screener endpoints — USER role
                        .requestMatchers(
                                "/api/elliott-screener/**",
                                "/api/elliott-suggestions/**",
                                "/api/scan/**"
                        ).hasAnyRole("USER", "MODERATOR", "ADMIN")

                        // All other endpoints require authentication
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Arrays.asList("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(corsConfigurationSource()));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
        return bean;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
