package com.dtech.aitrader.v2.memsys;

import com.dtech.aitrader.v2.memsys.auth.MemsysOAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * memsys MCP client over plain HTTPS JSON-RPC 2.0.
 *
 * <p>Auth resolution:
 * <ol>
 *   <li>If {@code userId} is non-null: {@link MemsysOAuthService#getValidAccessToken(Long)}
 *       returns the user's OAuth token; auto-refreshed when expiring.</li>
 *   <li>Otherwise: falls back to {@code memsys.bearer-token} property (operator/script use).</li>
 * </ol>
 *
 * <p>On HTTP 401 from memsys: force a refresh via
 * {@link MemsysOAuthService#forceRefresh(Long)} and retry the call once.
 */
@Component
@Slf4j
public class MemsysHttpClient implements MemsysClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final MemsysOAuthService oauthService;
    private final String endpoint;
    private final String fallbackBearer;

    public MemsysHttpClient(
            ObjectMapper mapper,
            MemsysOAuthService oauthService,
            @Value("${memsys.endpoint:https://memsys.dheemantech.in/mcp}") String endpoint,
            @Value("${memsys.bearer-token:}") String fallbackBearer) {
        this.mapper = mapper;
        this.oauthService = oauthService;
        this.endpoint = endpoint;
        this.fallbackBearer = fallbackBearer;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    // ── public surface ───────────────────────────────────────────────

    @Override
    public MemsysWriteResult writeMemory(Long userId, String content, String type, List<String> tags,
                                          Map<String, Object> metadata,
                                          String parentId, String supersedes,
                                          boolean forceNew,
                                          Boolean indexable,
                                          Instant expiresAt) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("content", content);
        if (type != null) args.put("type", type);
        if (tags != null && !tags.isEmpty()) args.put("tags", tags);
        if (metadata != null && !metadata.isEmpty()) args.put("metadata", metadata);
        if (parentId != null && !parentId.isBlank()) args.put("parent_id", parentId);
        if (supersedes != null && !supersedes.isBlank()) args.put("supersedes", supersedes);
        if (forceNew) args.put("force_new", true);
        if (indexable != null) args.put("indexable", indexable);
        if (expiresAt != null) args.put("expires_at", expiresAt.toString());
        return mapper.convertValue(call(userId, "memory_write", args), MemsysWriteResult.class);
    }

    @Override
    public MemsysWriteResult updateMemory(Long userId, String id, String content, List<String> tags,
                                           Map<String, Object> metadata, String tagsOp) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", id);
        if (content != null) args.put("content", content);
        if (tags != null) args.put("tags", tags);
        if (metadata != null) args.put("metadata", metadata);
        if (tagsOp != null) args.put("tags_op", tagsOp);
        return mapper.convertValue(call(userId, "memory_update", args), MemsysWriteResult.class);
    }

    @Override
    public MemsysWriteResult supersedeMemory(Long userId, String oldId, String newId) {
        return mapper.convertValue(call(userId, "memory_supersede", Map.of("old_id", oldId, "new_id", newId)),
                MemsysWriteResult.class);
    }

    @Override
    public MemsysMemory getMemory(Long userId, String id) {
        JsonNode node = call(userId, "memory_get", Map.of("id", id));
        JsonNode memory = node.has("memory") ? node.get("memory") : node;
        return mapper.convertValue(memory, MemsysMemory.class);
    }

    @Override
    public List<MemsysMemory> searchMemories(Long userId, String query, List<String> tags, String type,
                                              String parentId, Instant since, Instant until, int limit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", query);
        if (tags != null && !tags.isEmpty()) args.put("tags", tags);
        if (type != null) args.put("type", type);
        if (parentId != null && !parentId.isBlank()) args.put("parent_id", parentId);
        if (since != null) args.put("since", since.toString());
        if (until != null) args.put("until", until.toString());
        args.put("limit", limit);
        JsonNode node = call(userId, "memory_search", args);
        JsonNode results = node.has("results") ? node.get("results") : node;
        List<MemsysMemory> out = new ArrayList<>();
        if (results != null && results.isArray()) {
            for (JsonNode r : results) {
                out.add(mapper.convertValue(r, MemsysMemory.class));
            }
        }
        return out;
    }

    @Override
    public ThreadResult getThread(Long userId, String rootId) {
        JsonNode node = call(userId, "memory_thread_get", Map.of("root_id", rootId));
        MemsysMemory root = node.has("root") && !node.get("root").isNull()
                ? mapper.convertValue(node.get("root"), MemsysMemory.class)
                : null;
        List<MemsysMemory> replies = new ArrayList<>();
        if (node.has("replies") && node.get("replies").isArray()) {
            for (JsonNode r : node.get("replies")) {
                replies.add(mapper.convertValue(r, MemsysMemory.class));
            }
        }
        return new ThreadResult(root, replies);
    }

    @Override
    public JsonNode rawToolCall(Long userId, String toolName, Map<String, Object> arguments) {
        return call(userId, toolName, arguments);
    }

    // ── transport with retry-on-401 ──────────────────────────────────

    private JsonNode call(Long userId, String toolName, Map<String, Object> arguments) {
        String token = resolveToken(userId);
        try {
            return doCall(token, toolName, arguments);
        } catch (MemsysException e) {
            // 401 → force refresh + retry once. Only when we have a userId to refresh against.
            if (userId != null && e.getCode() == 401) {
                log.info("[memsys] got 401 for tool={} userId={} — forcing refresh + retrying", toolName, userId);
                Optional<String> fresh = oauthService.forceRefresh(userId);
                if (fresh.isPresent()) {
                    return doCall(fresh.get(), toolName, arguments);
                }
            }
            throw e;
        }
    }

    private String resolveToken(Long userId) {
        if (userId != null) {
            Optional<String> t = oauthService.getValidAccessToken(userId);
            if (t.isPresent()) return t.get();
            // No OAuth config for this user — fall through to fallback token if present
            log.debug("[memsys] no OAuth config for userId={}, falling back to static bearer", userId);
        }
        if (fallbackBearer != null && !fallbackBearer.isBlank()) return fallbackBearer;
        throw new MemsysException("no memsys credentials available — "
                + (userId == null
                ? "set memsys.bearer-token or call with userId"
                : "user " + userId + " has not connected memsys"));
    }

    private JsonNode doCall(String bearer, String toolName, Map<String, Object> arguments) {
        String requestId = UUID.randomUUID().toString();
        ObjectNode rpc = mapper.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", requestId);
        rpc.put("method", "tools/call");
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", mapper.valueToTree(arguments == null ? Map.of() : arguments));
        rpc.set("params", params);

        String body;
        try {
            body = mapper.writeValueAsString(rpc);
        } catch (Exception e) {
            throw new MemsysException("failed to serialize JSON-RPC body for " + toolName, e);
        }

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null && !bearer.isBlank()) {
            req.header("Authorization", "Bearer " + bearer);
        }

        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new MemsysException("memsys MCP transport failure for " + toolName, e);
        }

        if (res.statusCode() == 401 || res.statusCode() == 403) {
            throw new MemsysException(401, toolName + " — HTTP " + res.statusCode() + " unauthorized");
        }
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new MemsysException("memsys MCP HTTP " + res.statusCode() + " for " + toolName
                    + ": " + truncate(res.body(), 400));
        }

        JsonNode root;
        try {
            root = mapper.readTree(res.body());
        } catch (Exception e) {
            throw new MemsysException("memsys MCP returned malformed JSON for " + toolName + ": "
                    + truncate(res.body(), 400), e);
        }

        if (root.has("error") && !root.get("error").isNull()) {
            JsonNode err = root.get("error");
            int code = err.path("code").asInt(0);
            String message = err.path("message").asText("(no message)");
            JsonNode data = err.path("data");
            String dataStr = (data.isMissingNode() || data.isNull()) ? "" : " · data=" + data.toString();
            throw new MemsysException(code, toolName + " — " + message + dataStr);
        }

        // result.content[0].text is JSON-encoded tool output — unwrap one level.
        JsonNode result = root.path("result");
        if (!result.isObject()) {
            throw new MemsysException(toolName + " — missing result object");
        }
        JsonNode content = result.path("content");
        if (content.isArray() && content.size() > 0) {
            String text = content.get(0).path("text").asText(null);
            if (text != null) {
                try {
                    return mapper.readTree(text);
                } catch (Exception e) {
                    return content.get(0);
                }
            }
        }
        return result;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
