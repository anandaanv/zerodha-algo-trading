package com.dtech.algo.screener;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScreenerRegistryService {

    @Value("${screener.directory:screener}")
    private String screenerDir;

    @Getter
    private final Map<String, ScreenerScript> registry = new ConcurrentHashMap<>();
    private final Map<Long, ScreenerScript> registryById = new ConcurrentHashMap<>();

    // Cached Kotlin JSR-223 engine (initialized once at startup)
    private javax.script.ScriptEngine kotlinEngine;

    @PostConstruct
    public void loadScripts() {
        Path dir = Paths.get(screenerDir);
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.info("Created screener directory at {}", dir.toAbsolutePath());
            } catch (IOException e) {
                log.error("Failed to create screener directory {}: {}", dir, e.getMessage(), e);
            }
        }

        List<Path> scripts = discoverScripts(dir);
        if (scripts.isEmpty()) {
            log.info("No .kscr.kts scripts found under {}", dir.toAbsolutePath());
        }

        // Initialize Kotlin JSR-223 engine once with the thread context ClassLoader (works with Boot/DevTools)
        initEngineIfRequired();
        if (this.kotlinEngine == null) {
            log.warn("Kotlin JSR-223 engine not found on classpath. Skipping all .kscr.kts scripts under {}", dir.toAbsolutePath());
            log.info("Screener registry initialized. Compiled: 0, total registered: {}", registry.size());
            return;
        }

        int compiled = 0;
        for (Path p : scripts) {
            String name = deriveName(p);
            try {
                ScreenerScript script = compileKotlinScriptFromFile(p);
                if (script != null) {
                    registry.put(name, script);
                    compiled++;
                    log.info("Loaded screener script: {}", name);
                }
            } catch (Exception ex) {
                log.error("Failed to compile screener {}: {}", name, ex.getMessage(), ex);
            }
        }

        log.info("Screener registry initialized. Compiled: {}, total registered: {}", compiled, registry.size());
    }

    public Set<String> listNames() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    public void run(String name, ScreenerContext ctx, SignalCallback callback) {
        ScreenerScript script = registry.get(name);
        if (script == null) {
            throw new IllegalArgumentException("Screener not found: " + name);
        }
        script.evaluate(ctx, callback);
    }

    public void run(long id, ScreenerContext ctx, SignalCallback callback) {
        ScreenerScript script = registryById.get(id);
        if (script == null) {
            throw new IllegalArgumentException("Screener not registered for id: " + id);
        }
        script.evaluate(ctx, callback);
    }

    /**
     * Registers/compiles a Kotlin screener by id from raw script text.
     */
    public void registerScript(long id, String code) {
        initEngineIfRequired();
        if (this.kotlinEngine == null) {
            throw new IllegalStateException("Kotlin JSR-223 engine not available on classpath.");
        }
        try {
            ScreenerScript compiled = compileKotlinScriptFromString(code);
            if (compiled != null) {
                registryById.put(id, compiled);
                log.info("Registered screener (id={}) into registry", id);
            }
        } catch (Exception e) {
            log.error("Failed to compile/register screener id=" + id + ": " + e.getMessage(), e);
            throw new IllegalArgumentException("Failed to compile/register screener id=" + id + ": " + e.getMessage(), e);
        }
    }

    /**
     * Validate a screener script without registering it.
     * Uses a fresh Kotlin engine instance to avoid any shared-state collisions.
     *
     * @throws IllegalArgumentException if compilation fails
     */
    public void validateScript(String code) {
        try {
            getNewEngine(ClassLoader.getSystemClassLoader()).eval(code);
        } catch (Exception e) {
            throw new IllegalArgumentException("Compilation error: " + e.getMessage(), e);
        }
    }

    private void logEngineParams() {
        System.out.println("Engine CL: " + kotlinEngine.getClass().getClassLoader());
        System.out.println("Intrinsics CL: " + kotlin.jvm.internal.Intrinsics.class.getClassLoader());
        System.out.println("This class CL: " + getClass().getClassLoader());
        System.out.println("TCCL: " + Thread.currentThread().getContextClassLoader());
    }

    /**
     * Renames a top-level function named 'screener' to the given unique name.
     * This is a lightweight text transform designed for typical scripts of the form:
     *   fun screener(ctx: ScreenerContext, cb: SignalCallback) = ...
     */
    private String renameTopLevelScreener(String src, String uniqueName) {
        if (src == null || src.isBlank()) return src;
        // Replace only the function declaration 'fun screener(' with the unique name
        // Keep whitespace tolerant; do not touch other identifiers
        return src.replaceFirst("(?m)\\bfun\\s+screener\\s*\\(", "fun " + uniqueName + "(");
    }

    private void initEngineIfRequired() {
        if (this.kotlinEngine == null) {
            ClassLoader tccl = ClassLoader.getSystemClassLoader();
            this.kotlinEngine = getNewEngine(tccl);
        }

    }

    private ScriptEngine getNewEngine(ClassLoader tccl) {
        ScriptEngineManager manager = new ScriptEngineManager(tccl);
        return manager.getEngineByName("kotlin");
    }

    private List<Path> discoverScripts(Path dir) {
        try {
            if (!Files.exists(dir)) return List.of();
            try (var stream = Files.walk(dir)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".kscr.kts"))
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            log.error("Error scanning screener directory {}: {}", dir, e.getMessage(), e);
            return List.of();
        }
    }

    private String deriveName(Path p) {
        String file = p.getFileName().toString();
        if (file.endsWith(".kscr.kts")) {
            file = file.substring(0, file.length() - ".kscr.kts".length());
        }
        return file;
    }

    /**
     * Compile a Kotlin script via JSR-223 from a file. The script must define:
     *   fun screener(ctx: com.dtech.algo.screener.ScreenerContext, cb: com.dtech.algo.screener.SignalCallback)
     */
    private ScreenerScript compileKotlinScriptFromFile(Path scriptPath) throws Exception {
        if (this.kotlinEngine == null) {
            // Engine unavailability already logged at load time
            return null;
        }
        String code = Files.readString(scriptPath, StandardCharsets.UTF_8);
        this.kotlinEngine.eval(code);
        if (!(this.kotlinEngine instanceof Invocable inv)) {
            log.warn("Kotlin engine is not Invocable. Skipping script: {}", scriptPath);
            return null;
        }
        return (ctx, callback) -> {
            try {
                inv.invokeFunction("screener", ctx, callback);
            } catch (NoSuchMethodException e) {
                log.error("Script {} missing function 'screener(ctx, cb)'.", scriptPath.getFileName());
            } catch (Throwable t) {
                log.error("Error executing script {}: {}", scriptPath.getFileName(), t.getMessage(), t);
            }
        };
    }

    /**
     * Compile a Kotlin script from raw string using the same engine.
     */
    private ScreenerScript compileKotlinScriptFromString(String code) throws Exception {
        this.kotlinEngine.eval(code);
        if (!(this.kotlinEngine instanceof Invocable inv)) {
            log.warn("Kotlin engine is not Invocable. Skipping code registration.");
            return null;
        }
        return (ctx, callback) -> {
            try {
                inv.invokeFunction("screener", ctx, callback);
            } catch (NoSuchMethodException e) {
                log.error("Registered script missing function 'screener(ctx, cb)'.");
            } catch (Throwable t) {
                log.error("Error executing registered script: {}", t.getMessage(), t);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private ScreenerResult toResult(Object ret) {
        if (ret instanceof ScreenerResult sr) return sr;
        if (ret instanceof Map<?, ?> m) {
            boolean entry = asBool(m.get("entry"));
            boolean exit = asBool(m.get("exit"));
            Double score = asDouble(m.get("score"));
            Set<String> tags = Optional.ofNullable((Collection<?>) m.get("tags"))
                    .map(c -> c.stream().map(String::valueOf).collect(Collectors.toSet()))
                    .orElseGet(Set::of);
            Map<String, Object> debug = Optional.ofNullable((Map<String, Object>) m.get("debug"))
                    .orElseGet(Map::of);
            return ScreenerResult.builder()
                    .entry(entry)
                    .exit(exit)
                    .score(score)
                    .tags(tags)
                    .debug(debug)
                    .build();
        }
        return ScreenerResult.builder().entry(false).exit(false)
                .debug(Map.of("error", "Unsupported return type: " + (ret == null ? "null" : ret.getClass().getName())))
                .build();
    }

    private boolean asBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return false;
        }

    private Double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (Exception ignore) {}
        }
        return null;
    }
}
