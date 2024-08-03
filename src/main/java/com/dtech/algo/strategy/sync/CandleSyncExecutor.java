package com.dtech.algo.strategy.sync;

import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.service.CandleFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class CandleSyncExecutor {

    private final CandleRepository candleRepository;
    private final CandleFacade candleFacade;

    private ExecutorService executorService;

    @PostConstruct
    public void initialize() {
        executorService = Executors.newFixedThreadPool(1);
    }

    public void submit(CandleSyncToken job) {
        executorService.submit(getSyncJob(job));
    }

    protected CandleSyncJob getSyncJob(CandleSyncToken job) {
        return new CandleSyncJob(candleRepository, candleFacade, job);
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        executorService.shutdown();
        executorService.awaitTermination(5, TimeUnit.MINUTES);
    }

}
