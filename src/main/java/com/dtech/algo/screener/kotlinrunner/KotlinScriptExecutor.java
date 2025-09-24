package com.dtech.algo.screener.kotlinrunner;

import org.springframework.stereotype.Component;

import javax.script.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class KotlinScriptExecutor implements AutoCloseable {
    private final ExecutorService pool;
    private final ThreadLocal<ScriptEngine> engines;

    public KotlinScriptExecutor() {
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

    public <T> T evalReturnObject(String code, String filenameHint) throws Exception {
        Future<T> f = (Future<T>) pool.submit(() -> {
            ScriptEngine eng = engines.get();
            ClassLoader prev = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(eng.getClass().getClassLoader());
                Compilable comp = (Compilable) eng;
                CompiledScript cs = comp.compile(code);
                ScriptContext sc = new SimpleScriptContext();
                if (filenameHint != null)
                    sc.setAttribute(ScriptEngine.FILENAME, filenameHint, ScriptContext.ENGINE_SCOPE);
                return (T) cs.eval(sc); // typically returns your entry object
            } finally {
                Thread.currentThread().setContextClassLoader(prev);
            }
        });
        return f.get();
    }

    public <T> T invokeMethod(Object target, String method, Object... args) throws Exception {
        Future<T> f = (Future<T>) pool.submit(() -> {
            ScriptEngine eng = engines.get();
            ClassLoader prev = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(eng.getClass().getClassLoader());
                System.out.println("engine CL = " + eng.getClass().getClassLoader());
                System.out.println("ctx    CL = " + args[0].getClass().getClassLoader());
                System.out.println("cb     CL = " + args[1].getClass().getClassLoader());
                Invocable inv = (Invocable) eng;
                return (T) inv.invokeMethod(target, method, args);
            } finally {
                Thread.currentThread().setContextClassLoader(prev);
            }
        });
        return f.get();
    }

    @Override public void close() { pool.shutdownNow(); }
}
