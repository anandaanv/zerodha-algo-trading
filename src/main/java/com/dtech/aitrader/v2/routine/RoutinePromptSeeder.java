package com.dtech.aitrader.v2.routine;

import com.dtech.aitrader.v2.memsys.MemsysClient;
import com.dtech.aitrader.v2.memsys.MemsysMemory;
import com.dtech.aitrader.v2.memsys.MemsysWriteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Publishes the nightly-review routine prompt to memsys at boot under the seed user's tenant.
 *
 * The routine prompt is the instruction set a claude.ai scheduled task fetches at fire time —
 * it tells the AI how to review {@code ai-trader-scan-context} bundles and write back
 * {@code ai-trader-plan-group} memories.
 *
 * Idempotent: if a memory with the same routine + version tags already exists for the seed
 * user, this seeder no-ops. To roll out a new version, change the version constant and the
 * markdown file; the old memory is left in place (caller can supersede if desired).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoutinePromptSeeder {

    private static final String ROUTINE_TAG = "routine:nightly-review";
    private static final String VERSION_TAG = "version:v2";
    private static final String RESOURCE_PATH = "classpath:routines/nightly-review-v2.md";

    private final MemsysClient memsys;
    private final ResourceLoader resourceLoader;

    @Value("${routine.seed.enabled:true}")
    private boolean enabled;

    @Value("${routine.seed.user-id:1}")
    private Long seedUserId;

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        if (!enabled) {
            log.info("[routine-seeder] disabled (routine.seed.enabled=false)");
            return;
        }
        try {
            String content = loadResource(RESOURCE_PATH);
            List<MemsysMemory> candidates = memsys.searchMemories(
                    seedUserId,
                    "nightly-review routine prompt",
                    List.of(ROUTINE_TAG, VERSION_TAG),
                    "snippet",
                    null, null, null, 10);
            // memsys.searchMemories treats multiple tags as OR — post-filter for AND.
            List<MemsysMemory> exact = candidates.stream()
                    .filter(m -> m.getTags() != null
                            && m.getTags().contains(ROUTINE_TAG)
                            && m.getTags().contains(VERSION_TAG))
                    .toList();
            if (!exact.isEmpty()) {
                log.info("[routine-seeder] memory already exists (id={}) for {} {} — skipping",
                        exact.get(0).getId(), ROUTINE_TAG, VERSION_TAG);
                return;
            }
            log.info("[routine-seeder] no existing memory found for {} {} ({} candidates matched only one tag) — publishing",
                    ROUTINE_TAG, VERSION_TAG, candidates.size());
            MemsysWriteResult result = memsys.writeMemory(
                    seedUserId,
                    content,
                    "snippet",
                    List.of("project:ai-trader-v2", ROUTINE_TAG, VERSION_TAG, "kind:prompt"),
                    Map.of("seeded_at", Instant.now().toString(),
                            "source", "algotrade-startup",
                            "resource", RESOURCE_PATH),
                    null,
                    null,
                    false,
                    /*indexable*/ null,
                    /*expiresAt*/ null);
            log.info("[routine-seeder] published routine prompt id={} for userId={}",
                    result.getId(), seedUserId);
        } catch (Exception e) {
            log.warn("[routine-seeder] seeding failed: {}", e.getMessage());
        }
    }

    private String loadResource(String path) throws Exception {
        Resource r = resourceLoader.getResource(path);
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
