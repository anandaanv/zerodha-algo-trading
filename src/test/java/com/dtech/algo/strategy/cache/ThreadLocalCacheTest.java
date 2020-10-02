package com.dtech.algo.strategy.cache;

import static org.junit.jupiter.api.Assertions.*;

import com.dtech.algo.strategy.builder.cache.ThreadLocalCache;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ThreadLocalCacheTest {

  @InjectMocks
  private ThreadLocalCache<String, String> constantsCache;

  @Test
  public void getConstant() {
    constantsCache.put("a", "value");
    assertEquals(constantsCache.get("a"), "value");
  }

  @Test
  public void reset() {
    getConstant();
    constantsCache.reset();
    assertEquals(constantsCache.get("a"), null);
  }

  @Test
  public void threadSafety() throws ExecutionException, InterruptedException {
    getConstant();
    ExecutorService exec = Executors.newSingleThreadExecutor();
    assertNull(exec.submit(() -> constantsCache.get("a")).get());
    exec.shutdown();
    exec.awaitTermination(1, TimeUnit.SECONDS);
  }


}