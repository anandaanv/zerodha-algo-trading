package com.dtech.algo.strategy.sync;

import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class CandleSyncExecutor {

    private ExecutorService executorService;

    @PostConstruct
    public void initialize() {
        executorService = Executors.newFixedThreadPool(1);
    }

    public void submit(CandleSyncJob job) {
        executorService.submit(job);
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        executorService.shutdown();
        executorService.awaitTermination(5, TimeUnit.MINUTES);
    }
}
