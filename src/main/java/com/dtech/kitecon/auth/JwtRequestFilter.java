package com.dtech.kitecon.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtRequestFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final ServiceTokenService serviceTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // Check for service token first (query param or header)
        String requestServiceToken = request.getParameter("servicetoken");
        if (requestServiceToken == null) {
            requestServiceToken = request.getHeader("X-Service-Token");
        }

        if (requestServiceToken != null && !requestServiceToken.isEmpty()) {
            if (serviceTokenService.isValidToken(requestServiceToken)) {
                // Grant MODERATOR access for service token
                var authority = new SimpleGrantedAuthority("ROLE_MODERATOR");
                var authenticationToken = new UsernamePasswordAuthenticationToken(
                        "service-account", null, Collections.singletonList(authority));
                authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                chain.doFilter(request, response);
                return;
            }
        }

        // Regular JWT authentication
        final String authorizationHeader = request.getHeader("Authorization");

        String username = null;
        String jwt = null;

        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            jwt = authorizationHeader.substring(7);
            try {
                username = jwtUtil.extractUsername(jwt);
            } catch (Exception e) {
                // Invalid token
            }
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            var userOptional = userRepository.findByUsername(username);

            if (userOptional.isPresent() && jwtUtil.validateToken(jwt, username)) {
                User user = userOptional.get();

                log.error("User {} logged in with JWT token, for url {}", username, request.getRequestURL());
                // Only allow if user is enabled
                if (user.getEnabled()) {
                    var authority = new SimpleGrantedAuthority("ROLE_" + user.getRole().name());
                    var authenticationToken = new UsernamePasswordAuthenticationToken(
                            username, null, Collections.singletonList(authority));
                    authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                }
            }
        }

        chain.doFilter(request, response);
    }
}
