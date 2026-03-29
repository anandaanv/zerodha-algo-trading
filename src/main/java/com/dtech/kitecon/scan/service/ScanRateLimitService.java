package com.dtech.kitecon.scan.service;

import com.dtech.kitecon.scan.exception.RateLimitExceededException;
import com.dtech.kitecon.scan.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScanRateLimitService {

    private static final int SYSTEM_DEFAULT_LIMIT = 5;

    private final UserScanConfigRepository userScanConfigRepository;
    private final ScanGroupMemberRepository groupMemberRepository;
    private final ScanGroupRepository groupRepository;
    private final OnDemandScanRepository scanRepository;

    public int getEffectiveLimit(Long userId) {
        int limit = SYSTEM_DEFAULT_LIMIT;

        // User-level override
        var userConfig = userScanConfigRepository.findById(userId).orElse(null);
        if (userConfig != null && userConfig.getMaxScansPerHour() != null) {
            limit = Math.max(limit, userConfig.getMaxScansPerHour());
        }

        // Group-level overrides (whichever group gives the highest limit wins)
        var memberships = groupMemberRepository.findByUserId(userId);
        for (var membership : memberships) {
            var group = groupRepository.findById(membership.getGroupId()).orElse(null);
            if (group != null && group.getMaxScansPerHour() != null) {
                limit = Math.max(limit, group.getMaxScansPerHour());
            }
        }

        return limit;
    }

    public void checkRateLimit(Long userId) {
        int limit = getEffectiveLimit(userId);
        long used = scanRepository.countByUserIdAndRequestedAtAfter(userId, Instant.now().minusSeconds(3600));
        if (used >= limit) {
            throw new RateLimitExceededException(
                "Rate limit exceeded: " + used + "/" + limit + " scans used in the last hour");
        }
    }
}
