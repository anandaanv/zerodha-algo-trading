package com.dtech.kitecon.web;

import com.dtech.kitecon.service.SubscriptionUpdaterJob;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/subscription-updater")
@RequiredArgsConstructor
public class SubscriptionUpdaterController {

    private final SubscriptionUpdaterJob job;

    @GetMapping("/enabled")
    public Map<String, Object> getEnabled() {
        return Map.of("enabled", job.isEnabled());
    }

    @PutMapping("/enabled")
    public Map<String, Object> setEnabled(@RequestParam boolean enabled) {
        job.setEnabled(enabled);
        return Map.of("enabled", job.isEnabled());
    }
}
