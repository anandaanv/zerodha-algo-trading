package com.dtech.kitecon.web;

import com.dtech.kitecon.service.PromptBuilderService;
import com.dtech.kitecon.service.model.PromptBuildRequest;
import com.dtech.kitecon.service.model.PromptBuildResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
@CrossOrigin
@Slf4j
public class PromptBuilderController {

    private final PromptBuilderService promptBuilderService;

    /**
     * Meta-AI endpoint for building custom system prompts.
     * POST /api/prompts/build
     */
    @PostMapping("/build")
    public ResponseEntity<PromptBuildResponse> build(
        @RequestBody PromptBuildRequest req,
        Authentication auth
    ) {
        if (req.getUserMessage() == null || req.getUserMessage().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            log.info("User {} building prompt, history size={}", auth.getName(),
                req.getHistory() == null ? 0 : req.getHistory().size());
            return ResponseEntity.ok(promptBuilderService.build(req));
        } catch (Exception e) {
            log.error("Error building prompt", e);
            return ResponseEntity.status(500).build();
        }
    }
}
