package com.dtech.aitrader.v2.memsys;

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
 * Endpoint defaults to {@code https://memsys.dheemantech.in/mcp} but is overridable
 * via {@code memsys.endpoint} / {@code memsys.bearer-token} in application.properties.
 *
 * Per JSON-RPC 2.0 + MCP semantics:
 *   POST endpoint
 *   body: {"jsonrpc":"2.0","id":<uuid>,"method":"tools/call","params":{"name":"memory_write","arguments":{...}}}
 *   200 response: {"jsonrpc":"2.0","id":<uuid>,"result":{"content":[{"type":"text","text":"<json>"}]}}
 *   on error: {"jsonrpc":"2.0","id":<uuid>,"error":{"code":<int>,"message":"<str>"}}
 *
 * The inner content[].text is itself JSON-encoded (the tool's return value). We unwrap one level.
 */
@Component
@Slf4j
public class MemsysHttpClient implements MemsysClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final String bearerToken;

    public MemsysHttpClient(
            ObjectMapper mapper,
            @Value("${memsys.endpoint:https://memsys.dheemantech.in/mcp}") String endpoint,
            @Value("${memsys.bearer-token:}") String bearerToken) {
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.bearerToken = bearerToken;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    // ── public surface ───────────────────────────────────────────────

    @Override
    public MemsysWriteResult writeMemory(String content, String type, List<String> tags,
                                          Map<String, Object> metadata,
                                          String parentId, String supersedes) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("content", content);
        if (type != null) args.put("type", type);
        if (tags != null && !tags.isEmpty()) args.put("tags", tags);
        if (metadata != null && !metadata.isEmpty()) args.put("metadata", metadata);
        if (parentId != null && !parentId.isBlank()) args.put("parent_id", parentId);
        if (supersedes != null && !supersedes.isBlank()) args.put("supersedes", supersedes);
        JsonNode node = call("memory_write", args);
        return mapper.convertValue(node, MemsysWriteResult.class);
    }

    @Override
    public MemsysWriteResult updateMemory(String id, String content, List<String> tags,
                                           Map<String, Object> metadata, String tagsOp) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", id);
        if (content != null) args.put("content", content);
        if (tags != null) args.put("tags", tags);
        if (metadata != null) args.put("metadata", metadata);
        if (tagsOp != null) args.put("tags_op", tagsOp);
        JsonNode node = call("memory_update", args);
        return mapper.convertValue(node, MemsysWriteResult.class);
    }

    @Override
    public MemsysWriteResult supersedeMemory(String oldId, String newId) {
        Map<String, Object> args = Map.of("old_id", oldId, "new_id", newId);
        JsonNode node = call("memory_supersede", args);
        return mapper.convertValue(node, MemsysWriteResult.class);
    }

    @Override
    public MemsysMemory getMemory(String id) {
        JsonNode node = call("memory_get", Map.of("id", id));
        JsonNode memory = node.has("memory") ? node.get("memory") : node;
        return mapper.convertValue(memory, MemsysMemory.class);
    }

    @Override
    public List<MemsysMemory> searchMemories(String query, List<String> tags, String type,
                                              String parentId, Instant since, Instant until, int limit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", query);
        if (tags != null && !tags.isEmpty()) args.put("tags", tags);
        if (type != null) args.put("type", type);
        if (parentId != null && !parentId.isBlank()) args.put("parent_id", parentId);
        if (since != null) args.put("since", since.toString());
        if (until != null) args.put("until", until.toString());
        args.put("limit", limit);
        JsonNode node = call("memory_search", args);
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
    public ThreadResult getThread(String rootId) {
        JsonNode node = call("memory_thread_get", Map.of("root_id", rootId));
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
    public JsonNode rawToolCall(String toolName, Map<String, Object> arguments) {
        return call(toolName, arguments);
    }

    // ── transport ────────────────────────────────────────────────────

    private JsonNode call(String toolName, Map<String, Object> arguments) {
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
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null && !bearerToken.isBlank()) {
            req.header("Authorization", "Bearer " + bearerToken);
        }

        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new MemsysException("memsys MCP transport failure for " + toolName, e);
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
            throw new MemsysException(code, toolName + " — " + message);
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
                    // content[].text wasn't JSON — return raw object instead
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
