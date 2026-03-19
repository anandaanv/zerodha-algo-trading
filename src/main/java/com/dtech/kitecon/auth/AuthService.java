package com.dtech.kitecon.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final GoogleOAuth2Service googleOAuth2Service;

    @Transactional
    public User registerUser(String username, String password, User.Role role) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists");
        }

        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .role(role)
                .enabled(true)
                .build();

        return userRepository.save(user);
    }

    public String authenticate(String username, String password) {
        Optional<User> userOptional = userRepository.findByUsername(username);

        if (userOptional.isEmpty()) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        User user = userOptional.get();

        if (!user.getEnabled()) {
            throw new IllegalArgumentException("User account is disabled");
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        return jwtUtil.generateToken(user.getUsername(), user.getRole());
    }

    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    /**
     * Authenticate user with Google ID token
     * Creates new user if doesn't exist
     *
     * @param googleIdToken The Google ID token
     * @return JWT token for the authenticated user
     */
    @Transactional
    public String authenticateWithGoogle(String googleIdToken) {
        try {
            // Verify Google ID token
            GoogleUserInfo googleUserInfo = googleOAuth2Service.verifyIdToken(googleIdToken);

            // Find or create user
            Optional<User> existingUser = userRepository.findByGoogleId(googleUserInfo.getGoogleId());

            User user;
            if (existingUser.isPresent()) {
                user = existingUser.get();
                log.info("Existing Google user logged in: {}", user.getEmail());

                // Update user info if changed
                if (!user.getEmail().equals(googleUserInfo.getEmail())) {
                    user.setEmail(googleUserInfo.getEmail());
                    userRepository.save(user);
                }
            } else {
                // Check if email already exists with different provider
                Optional<User> emailUser = userRepository.findByEmail(googleUserInfo.getEmail());
                if (emailUser.isPresent()) {
                    throw new IllegalArgumentException("Email already registered with different login method");
                }

                // Create new user
                String username = generateUsernameFromEmail(googleUserInfo.getEmail());

                user = User.builder()
                        .username(username)
                        .email(googleUserInfo.getEmail())
                        .googleId(googleUserInfo.getGoogleId())
                        .provider(User.AuthProvider.GOOGLE)
                        .role(User.Role.USER) // Default role for new OAuth users
                        .enabled(true)
                        .build();

                user = userRepository.save(user);
                log.info("New Google user registered: {}", user.getEmail());
            }

            if (!user.getEnabled()) {
                throw new IllegalArgumentException("User account is disabled");
            }

            return jwtUtil.generateToken(user.getUsername(), user.getRole());

        } catch (GeneralSecurityException | IOException e) {
            log.error("Failed to verify Google ID token", e);
            throw new IllegalArgumentException("Invalid Google ID token: " + e.getMessage());
        }
    }

    /**
     * Generate unique username from email
     */
    private String generateUsernameFromEmail(String email) {
        String baseUsername = email.split("@")[0];
        String username = baseUsername;
        int counter = 1;

        while (userRepository.existsByUsername(username)) {
            username = baseUsername + counter;
            counter++;
        }

        return username;
    }
}
