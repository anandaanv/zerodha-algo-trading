package com.dtech.kitecon.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

import java.util.Arrays;

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

                        // Legal pages (required for Google OAuth)
                        .requestMatchers("/privacy-policy", "/privacy-policy.html", "/privacy").permitAll()
                        .requestMatchers("/terms-of-service", "/terms-of-service.html", "/terms").permitAll()

                        // Kite login and app config pages (public for now)
                        .requestMatchers("/kite-login", "/api/trades").permitAll()

                        // Role-based access control
//                        .requestMatchers("/api/trades")
//                              .hasAnyRole("USER", "MODERATOR", "ADMIN")
                        // DELETE operations require MODERATOR/ADMIN
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/chart-state", "/api/layouts/**").hasAnyRole("MODERATOR", "ADMIN")
                        // Chart operations allow USER
                        .requestMatchers("/api/symbols", "/api/ohlc", "/api/chart-state/**", "/api/layouts/**").hasAnyRole("USER", "MODERATOR", "ADMIN")
                        .requestMatchers("/api/chart/**", "/api/screener/**", "/api/overlays/**")
                              .hasAnyRole("MODERATOR", "ADMIN")
                        .requestMatchers("/api/chart-state/export", "/api/chart-state/import", "/api/remote-sync/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**", "/app", "/update-token").hasRole("ADMIN")

                        // Snapshot and analysis endpoints - allow USER role
                        .requestMatchers("/api/snapshots/**", "/api/analysis/**", "/api/tags/**").hasAnyRole("USER", "MODERATOR", "ADMIN")

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
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
