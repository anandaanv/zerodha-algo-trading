package com.dtech.kitecon.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;

/**
 * In-memory service token management.
 * Token is generated once per day and stored in memory.
 */
@Service
@Slf4j
public class ServiceTokenService {

    private static final SecureRandom secureRandom = new SecureRandom();

    private String currentToken;
    private LocalDate tokenDate;

    /**
     * Get the current valid service token for today.
     * If no token exists for today, generate a new one.
     */
    public synchronized String getCurrentToken() {
        LocalDate today = LocalDate.now();

        if (currentToken == null || !today.equals(tokenDate)) {
            generateNewToken(today);
        }

        return currentToken;
    }

    /**
     * Generate a new service token for the specified date.
     */
    private synchronized void generateNewToken(LocalDate date) {
        currentToken = generateSecureToken();
        tokenDate = date;
        log.info("Generated new service token for date: {}", date);
    }

    /**
     * Rotate token - generate new one for today.
     */
    public synchronized String rotateToken() {
        LocalDate today = LocalDate.now();
        log.info("Manually rotating service token for date: {}", today);
        generateNewToken(today);
        return currentToken;
    }

    /**
     * Validate if a token is valid.
     */
    public synchronized boolean isValidToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        // Ensure we have a token for today
        getCurrentToken();

        // Check if token matches current token
        return token.equals(currentToken);
    }

    /**
     * Generate a cryptographically secure random token.
     */
    private String generateSecureToken() {
        byte[] randomBytes = new byte[32]; // 256 bits
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Get the date for which current token is valid.
     */
    public synchronized LocalDate getTokenDate() {
        getCurrentToken(); // Ensure token exists
        return tokenDate;
    }
}
