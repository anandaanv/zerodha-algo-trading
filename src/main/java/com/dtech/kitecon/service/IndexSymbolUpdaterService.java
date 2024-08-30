package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.repository.IndexSymbolRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@EnableAsync
public class IndexSymbolUpdaterService {

    @Autowired
    private DataFetchService dataFetchService;

//    @Value("${symbol.update.worker.thread.pool.size:10}")
       @Autowired
    private IndexSymbolRepository indexSymbolRepository;


    /**
     * Updates symbols for all intervals using worker threads. Each symbol is processed in a separate worker thread.
     */

    @Async
    public void updateSymbols() throws InterruptedException {
        List<String> allSymbols = indexSymbolRepository.findAllSymbols();

        // For each symbol, assign a worker thread to handle the updates
        for (String symbol : allSymbols) {
            updateSymbolForAllIntervals(symbol);
        }

        // Shutdown the executor service after submission
//        executorService.shutdown();
//        executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    /**
     * Updates all intervals for the given symbol within a transaction.
     */
    @Transactional
    public void updateSymbolForAllIntervals(String symbol) {
        String[] exchanges = {"NSE"}; // Assuming we're updating for NSE

        for (Interval interval : Interval.values()) {
            dataFetchService.updateInstrumentToLatest(symbol, interval, exchanges); // Updated method signature
        }
    }
}
