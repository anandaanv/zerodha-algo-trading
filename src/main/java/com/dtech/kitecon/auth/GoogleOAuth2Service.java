package com.dtech.kitecon.auth;

import com.dtech.kitecon.data.AppSecrets;
import com.dtech.kitecon.repository.AppSecretsRepository;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Optional;

/**
 * Service for validating Google OAuth2 ID tokens
 * Loads credentials from app_secrets table
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GoogleOAuth2Service {

    private final AppSecretsRepository appSecretsRepository;

    @Value("${google.oauth2.client-id:}")
    private String clientId;

    @Value("${SECRETS_ENV:dev}")
    private String secretsEnv;

    @PostConstruct
    public void init() {
        // Load from database if not set via environment variable
        if (clientId == null || clientId.trim().isEmpty()) {
            loadFromAppSecrets();
        }

        if (clientId == null || clientId.trim().isEmpty()) {
            log.warn("Google OAuth2 Client ID not configured. Google Sign-In will not work.");
        } else {
            log.info("Google OAuth2 Client ID loaded successfully");
        }
    }

    private void loadFromAppSecrets() {
        Optional<AppSecrets> clientIdOpt = appSecretsRepository.findByEnvAndPropKey(secretsEnv, "google.oauth2.client-id");

        clientIdOpt.ifPresent(s -> {
            this.clientId = s.getPropValue();
            log.info("Loaded Google OAuth2 Client ID from database for env: {}", secretsEnv);
        });
    }

    /**
     * Verify Google ID token and extract user information
     *
     * @param idTokenString The Google ID token string
     * @return GoogleUserInfo containing user details
     * @throws GeneralSecurityException if token verification fails
     * @throws IOException if network error occurs
     */
    public GoogleUserInfo verifyIdToken(String idTokenString) throws GeneralSecurityException, IOException {
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(),
                new GsonFactory())
                .setAudience(Collections.singletonList(clientId))
                .build();

        GoogleIdToken idToken = verifier.verify(idTokenString);
        if (idToken != null) {
            GoogleIdToken.Payload payload = idToken.getPayload();

            String googleId = payload.getSubject();
            String email = payload.getEmail();
            boolean emailVerified = payload.getEmailVerified();
            String name = (String) payload.get("name");
            String pictureUrl = (String) payload.get("picture");
            String givenName = (String) payload.get("given_name");
            String familyName = (String) payload.get("family_name");

            if (!emailVerified) {
                throw new IllegalArgumentException("Email not verified by Google");
            }

            log.info("Successfully verified Google ID token for user: {}", email);

            return GoogleUserInfo.builder()
                    .googleId(googleId)
                    .email(email)
                    .name(name)
                    .givenName(givenName)
                    .familyName(familyName)
                    .pictureUrl(pictureUrl)
                    .emailVerified(emailVerified)
                    .build();
        } else {
            log.error("Invalid Google ID token");
            throw new IllegalArgumentException("Invalid Google ID token");
        }
    }
}
