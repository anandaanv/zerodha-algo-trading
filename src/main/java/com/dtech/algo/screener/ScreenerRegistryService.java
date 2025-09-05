package com.dtech.algo.screener;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

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
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        ScriptEngineManager manager = new ScriptEngineManager(tccl);
        this.kotlinEngine = manager.getEngineByName("kotlin");
        if (this.kotlinEngine == null) {
            log.warn("Kotlin JSR-223 engine not found on classpath. Skipping all .kscr.kts scripts under {}", dir.toAbsolutePath());
            log.info("Screener registry initialized. Compiled: 0, total registered: {}", registry.size());
            return;
        }

        int compiled = 0;
        for (Path p : scripts) {
            String name = deriveName(p);
            try {
                ScreenerScript script = compileKotlinScript(p);
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
     * Compile a Kotlin script via JSR-223. The script must define:
     *   fun screener(ctx: com.dtech.algo.screener.ScreenerContext, cb: com.dtech.algo.screener.SignalCallback)
     */
    private ScreenerScript compileKotlinScript(Path scriptPath) throws Exception {
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

    // Built-in SMA crossover screener (fallback)
    private ScreenerResult smaCrossoverBuiltin(ScreenerContext ctx) {
        String alias = Optional.ofNullable(ctx.getParam("alias"))
                .map(Object::toString).orElse("base");
        int fast = Optional.ofNullable(ctx.getParam("fast"))
                .map(Object::toString).map(Integer::parseInt).orElse(9);
        int slow = Optional.ofNullable(ctx.getParam("slow"))
                .map(Object::toString).map(Integer::parseInt).orElse(21);

        BarSeries series = ctx.getSeries(alias);
        int i = ctx.getNowIndex();
        if (series == null) {
            return ScreenerResult.builder()
                    .entry(false).exit(false)
                    .debug(Map.of("warn", "series '" + alias + "' missing"))
                    .build();
        }
        if (i < 1) {
            return ScreenerResult.builder().entry(false).exit(false)
                    .debug(Map.of("warn", "insufficient bars")).build();
        }

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator f = new SMAIndicator(close, fast);
        SMAIndicator s = new SMAIndicator(close, slow);

        double fPrev = f.getValue(i - 1).doubleValue();
        double sPrev = s.getValue(i - 1).doubleValue();
        double fNow = f.getValue(i).doubleValue();
        double sNow = s.getValue(i).doubleValue();

        boolean entry = (fPrev <= sPrev) && (fNow > sNow);
        boolean exit = (fPrev >= sPrev) && (fNow < sNow);
        double score = fNow - sNow;

        return ScreenerResult.builder()
                .entry(entry)
                .exit(exit)
                .score(score)
                .tags(entry ? Set.of("sma-cross-up") : (exit ? Set.of("sma-cross-down") : Set.of()))
                .debug(Map.of("fast", fast, "slow", slow, "fNow", fNow, "sNow", sNow))
                .build();
    }
}
