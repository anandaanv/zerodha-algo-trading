package com.dtech.kitecon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.util.ClassUtils;

import java.util.concurrent.ThreadFactory;

@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    /** Expose the “app” ClassLoader (usually TomcatEmbeddedWebappClassLoader in requests,
     or RestartClassLoader under Devtools). */
    @Bean
    public ClassLoader appClassLoader() {
        // Uses TCCL if present, else system CL — good default for “app” code.
//        ClassLoader cl = ClassUtils.getDefaultClassLoader();
//        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        return ClassLoader.getSystemClassLoader();
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler(ClassLoader appClassLoader) {
        ThreadPoolTaskScheduler ts = new ThreadPoolTaskScheduler();
        ts.setPoolSize(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        ts.setThreadNamePrefix("sched-");

        // Ensure every scheduler thread runs with the *application* classloader.
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "sched-" + System.nanoTime());
            t.setDaemon(true);
            t.setContextClassLoader(appClassLoader);
            return t;
        };
        ts.setThreadFactory(tf);
        return ts;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler(appClassLoader()));
    }
}
