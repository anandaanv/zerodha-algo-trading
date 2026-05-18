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
 * Surface is the subset needed by the AI Trader v2 orchestrator and review flows
 * (see "AI Trader v2 — Implementation Plan v1.2" memory).
 */
public interface MemsysClient {

    MemsysWriteResult writeMemory(
            String content,
            String type,
            List<String> tags,
            Map<String, Object> metadata,
            /* nullable */ String parentId,
            /* nullable */ String supersedes);

    MemsysWriteResult updateMemory(
            String id,
            /* nullable */ String content,
            /* nullable */ List<String> tags,
            /* nullable */ Map<String, Object> metadata,
            /* nullable, "replace" | "add" | "remove" */ String tagsOp);

    MemsysWriteResult supersedeMemory(String oldId, String newId);

    MemsysMemory getMemory(String id);

    List<MemsysMemory> searchMemories(
            String query,
            /* nullable */ List<String> tags,
            /* nullable */ String type,
            /* nullable */ String parentId,
            /* nullable */ Instant since,
            /* nullable */ Instant until,
            int limit);

    /**
     * Get root + ordered replies for a threaded memory. Replies are sorted by created_at ASC.
     * The result's first element is the root; subsequent elements are replies in chronological
     * order. May return an empty list if the id doesn't exist.
     */
    ThreadResult getThread(String rootId);

    record ThreadResult(MemsysMemory root, List<MemsysMemory> replies) {
        /** Returns true when the underlying lookup found nothing. */
        public boolean isEmpty() { return root == null; }
    }

    /** Convenience: raw JSON-RPC tools/call passthrough — useful for tools we haven't typed yet. */
    JsonNode rawToolCall(String toolName, Map<String, Object> arguments);
}
