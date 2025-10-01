package com.dtech.algo.screener.kotlinrunner;

import com.dtech.algo.screener.enums.WorkflowStep;
import com.dtech.algo.screener.runtime.ScreenerRunLogService;
import org.springframework.stereotype.Component;

import javax.script.*;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class KotlinScriptExecutor implements AutoCloseable {
    private final ExecutorService pool;
    private final ThreadLocal<ScriptEngine> engines;
    private final ScreenerRunLogService runLogService;

    // Optional: caller can set current runId in this thread prior to invocation
    private final ThreadLocal<Long> currentRunId = new ThreadLocal<>();

    public KotlinScriptExecutor(ScreenerRunLogService runLogService) {
        this.runLogService = runLogService;
        ClassLoader engineCL = ClassLoader.getSystemClassLoader(); // isolate from RestartCL
        this.pool = Executors.newFixedThreadPool(4, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "kts-worker-" + n.incrementAndGet());
                t.setDaemon(true);
                t.setContextClassLoader(engineCL);
                return t;
            }
        });
        this.engines = ThreadLocal.withInitial(() -> {
            // Create a Kotlin JSR-223 engine bound to THIS thread's TCCL
            ClassLoader prev = Thread.currentThread().getContextClassLoader();
            try {
                // Use TCCL so kotlin-scripting-jsr223 finds services
                Thread.currentThread().setContextClassLoader(engineCL);
                ScriptEngineManager mgr = new ScriptEngineManager(engineCL);
                ScriptEngine eng = mgr.getEngineByName("kotlin"); // or "kts"
                if (eng == null) throw new IllegalStateException("Kotlin JSR-223 engine not found");
                return eng;
            } finally {
                Thread.currentThread().setContextClassLoader(prev);
            }
        });
    }

    /**
     * Optionally set/clear the current runId for subsequent eval/invoke calls on this thread.
     */
    public void setCurrentRunId(Long runId) { currentRunId.set(runId); }
    public void clearCurrentRunId() { currentRunId.remove(); }

    public <T> T evalReturnObject(String code, String filenameHint) throws Exception {
        Future<T> f = (Future<T>) pool.submit(() -> {
            ScriptEngine eng = engines.get();
            ClassLoader prevCL = Thread.currentThread().getContextClassLoader();
            Writer prevOut = eng.getContext().getWriter();
            Writer prevErr = eng.getContext().getErrorWriter();
            try {
                Thread.currentThread().setContextClassLoader(eng.getClass().getClassLoader());

                // Attach DB-backed writers for script stdout/stderr
                Long runId = resolveRunId(); // from ThreadLocal if set
                eng.getContext().setWriter(new ScriptLogWriter(runLogService, runId, WorkflowStep.SCRIPT, "stdout", prevOut));
                eng.getContext().setErrorWriter(new ScriptLogWriter(runLogService, runId, WorkflowStep.SCRIPT, "stderr", prevErr));

                Compilable comp = (Compilable) eng;
                CompiledScript cs = comp.compile(code);
                ScriptContext sc = new SimpleScriptContext();
                if (filenameHint != null)
                    sc.setAttribute(ScriptEngine.FILENAME, filenameHint, ScriptContext.ENGINE_SCOPE);
                T entry = (T) cs.eval(sc);
                return entry; // typically returns your entry object
            } finally {
                // restore writers and classloader
                try { eng.getContext().setWriter(prevOut); } catch (Throwable ignore) {}
                try { eng.getContext().setErrorWriter(prevErr); } catch (Throwable ignore) {}
                Thread.currentThread().setContextClassLoader(prevCL);
            }
        });
        return f.get();
    }

    public <T> T invokeMethod(Object target, String method, Object... args) throws Exception {
        Future<T> f = (Future<T>) pool.submit(() -> {
            ScriptEngine eng = engines.get();
            ClassLoader prevCL = Thread.currentThread().getContextClassLoader();
            Writer prevOut = eng.getContext().getWriter();
            Writer prevErr = eng.getContext().getErrorWriter();
            try {
                Thread.currentThread().setContextClassLoader(eng.getClass().getClassLoader());

                // Resolve runId from ThreadLocal or from args[0] context params (best effort)
                Long runId = resolveRunId(args);
                eng.getContext().setWriter(new ScriptLogWriter(runLogService, runId, WorkflowStep.SCRIPT, "stdout", prevOut));
                eng.getContext().setErrorWriter(new ScriptLogWriter(runLogService, runId, WorkflowStep.SCRIPT, "stderr", prevErr));

                Invocable inv = (Invocable) eng;
                return (T) inv.invokeMethod(target, method, args);
            } finally {
                // restore writers and classloader
                try { eng.getContext().setWriter(prevOut); } catch (Throwable ignore) {}
                try { eng.getContext().setErrorWriter(prevErr); } catch (Throwable ignore) {}
                Thread.currentThread().setContextClassLoader(prevCL);
            }
        });
        return f.get();
    }

    private Long resolveRunId(Object... args) {
        Long fromTL = currentRunId.get();
        if (fromTL != null && fromTL > 0) return fromTL;
        if (args != null && args.length > 0 && args[0] != null) {
            Object ctx = args[0];
            // Try: ctx.getRunId()
            try {
                Method m = ctx.getClass().getMethod("getRunId");
                Object v = m.invoke(ctx);
                if (v instanceof Number) return ((Number) v).longValue();
                if (v instanceof String s && !s.isBlank()) return Long.parseLong(s.trim());
            } catch (Throwable ignore) {}
            // Try: ctx.getParams().get("runId")
            try {
                Method m = ctx.getClass().getMethod("getParams");
                Object mapObj = m.invoke(ctx);
                if (mapObj instanceof Map<?,?> p) {
                    Object v = p.get("runId");
                    if (v instanceof Number) return ((Number) v).longValue();
                    if (v instanceof String s && !s.isBlank()) return Long.parseLong(s.trim());
                }
            } catch (Throwable ignore) {}
        }
        return null;
    }

    @Override public void close() { pool.shutdownNow(); }
}
