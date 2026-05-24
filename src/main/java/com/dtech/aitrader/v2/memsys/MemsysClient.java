package com.dtech.aitrader.v2.memsys;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Typed wrapper around the memsys MCP server. Implementations call the JSON-RPC 2.0
 * tools/call endpoint exposed at memsys.dheemantech.in/mcp (or wherever configured) and
 * translate transport/JSON errors into {@link MemsysException}.
 *
 * <p>Every method takes {@code userId} so the implementation can resolve the right
 * per-user OAuth access token via {@link com.dtech.aitrader.v2.memsys.auth.MemsysOAuthService}.
 * memsys uses the token to identify the tenant (JWT.sub → tenant_identities → RLS) — we
 * never pass user_id in the tool arguments.
 *
 * <p>{@code userId} can be {@code null} for system-level calls when a static
 * {@code memsys.bearer-token} property is configured (operator scripts, smoke tests).
 */
public interface MemsysClient {

    MemsysWriteResult writeMemory(
            Long userId,
            String content,
            String type,
            List<String> tags,
            Map<String, Object> metadata,
            /* nullable */ String parentId,
            /* nullable */ String supersedes,
            boolean forceNew,
            /* nullable */ Boolean indexable,
            /* nullable */ Instant expiresAt);

    MemsysWriteResult updateMemory(
            Long userId,
            String id,
            /* nullable */ String content,
            /* nullable */ List<String> tags,
            /* nullable */ Map<String, Object> metadata,
            /* nullable, "replace" | "add" | "remove" */ String tagsOp);

    MemsysWriteResult supersedeMemory(Long userId, String oldId, String newId);

    MemsysMemory getMemory(Long userId, String id);

    List<MemsysMemory> searchMemories(
            Long userId,
            String query,
            /* nullable */ List<String> tags,
            /* nullable */ String type,
            /* nullable */ String parentId,
            /* nullable */ Instant since,
            /* nullable */ Instant until,
            int limit);

    /**
     * Get root + ordered replies for a threaded memory. Replies are sorted by created_at ASC.
     */
    ThreadResult getThread(Long userId, String rootId);

    record ThreadResult(MemsysMemory root, List<MemsysMemory> replies) {
        public boolean isEmpty() { return root == null; }
    }

    /** Raw JSON-RPC tools/call passthrough — useful for tools we haven't typed yet. */
    JsonNode rawToolCall(Long userId, String toolName, Map<String, Object> arguments);
}
