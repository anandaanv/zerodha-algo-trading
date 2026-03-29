package com.dtech.kitecon.screener.service;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.scan.service.ScanGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ScreenerAccessService {

    private final ScanGroupService scanGroupService;
    private final UserRepository userRepository;

    /**
     * Throws AccessDeniedException if the authenticated user does not have screener access.
     * ADMIN and MODERATOR roles always have access.
     */
    public void requireScreenerAccess(Authentication auth) {
        if (auth == null) throw new AccessDeniedException("Not authenticated");

        boolean isAdminOrModerator = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_MODERATOR"));
        if (isAdminOrModerator) return;

        Long userId = userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new AccessDeniedException("User not found"));

        if (!scanGroupService.userHasScreenerAccess(userId)) {
            throw new AccessDeniedException("Screener access not granted. Contact admin to be added to a screener group.");
        }
    }

    /**
     * Returns true if the user has screener access (no exception thrown).
     */
    public boolean hasScreenerAccess(Authentication auth) {
        try {
            requireScreenerAccess(auth);
            return true;
        } catch (AccessDeniedException e) {
            return false;
        }
    }
}
