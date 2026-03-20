package com.dtech.algo.config;

import com.dtech.kitecon.auth.JwtRequestFilter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import javax.crypto.SecretKey;
import java.util.List;

/**
 * Spring STOMP/WebSocket configuration.
 *
 * Endpoint:  ws://{host}/ws   (native WebSocket, no SockJS required)
 * Topics:    /topic/**        (server → client broadcasts)
 * App prefix: /app/**         (client → server messages, reserved for future use)
 *
 * Auth: JWT token passed as STOMP CONNECT header "Authorization: Bearer <token>"
 * Falls back to unauthenticated if no token (allows public subscriptions in future).
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${jwt.secret:defaultSecretKeyForDevelopmentOnlyReplaceInProduction}")
    private String jwtSecret;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*"); // restrict in production
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String authHeader = accessor.getFirstNativeHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        String token = authHeader.substring(7);
                        try {
                            SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                                    jwtSecret.getBytes());
                            Claims claims = Jwts.parser()
                                    .verifyWith(key)
                                    .build()
                                    .parseSignedClaims(token)
                                    .getPayload();

                            String username = claims.getSubject();
                            String role = claims.get("role", String.class);
                            List<SimpleGrantedAuthority> authorities = role != null
                                    ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                                    : List.of();

                            UsernamePasswordAuthenticationToken auth =
                                    new UsernamePasswordAuthenticationToken(username, null, authorities);
                            accessor.setUser(auth);
                        } catch (Exception e) {
                            log.warn("WebSocket CONNECT: invalid JWT token - {}", e.getMessage());
                        }
                    }
                }
                return message;
            }
        });
    }
}
