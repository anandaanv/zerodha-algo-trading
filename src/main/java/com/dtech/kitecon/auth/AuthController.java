package com.dtech.kitecon.auth;

import com.dtech.kitecon.scan.service.ScanGroupService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final ScanGroupService scanGroupService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            String token = authService.authenticate(request.getUsername(), request.getPassword());
            User user = authService.getUserByUsername(request.getUsername());

            return ResponseEntity.ok(new LoginResponse(
                    token,
                    user.getUsername(),
                    user.getRole().name()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        try {
            // Always register as USER role by default, ignore role parameter
            User user = authService.registerUser(request.getUsername(), request.getPassword(), User.Role.USER);
            String token = jwtUtil.generateToken(user.getUsername(), user.getRole());

            return ResponseEntity.ok(new LoginResponse(
                    token,
                    user.getUsername(),
                    user.getRole().name()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@RequestBody GoogleLoginRequest request) {
        try {
            String token = authService.authenticateWithGoogle(request.getIdToken());
            User user = authService.getUserByUsername(
                    jwtUtil.extractUsername(token)
            );

            return ResponseEntity.ok(new LoginResponse(
                    token,
                    user.getUsername(),
                    user.getRole().name()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(new ErrorResponse("Not authenticated"));
        }

        String username = authentication.getName();
        User user = authService.getUserByUsername(username);

        boolean screenerAccess = user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.MODERATOR
                || scanGroupService.userHasScreenerAccess(user.getId());
        return ResponseEntity.ok(new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getRole().name(),
                screenerAccess
        ));
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }

    @Data
    public static class RegisterRequest {
        private String username;
        private String password;
        private String role; // USER, MODERATOR, ADMIN
    }

    @Data
    public static class LoginResponse {
        private final String token;
        private final String username;
        private final String role;
    }

    @Data
    public static class UserResponse {
        private final Long id;
        private final String username;
        private final String role;
        private final boolean screenerAccess;
    }

    @Data
    public static class GoogleLoginRequest {
        private String idToken;
    }

    @Data
    public static class ErrorResponse {
        private final String error;
    }
}
