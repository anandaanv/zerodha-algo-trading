package com.dtech.algo.strategy.builder.cache;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ThreadLocalCache<K, V> {

  private static ThreadLocal<Map<Object, Object>> threadLocal = new ThreadLocal<>();

  private Map<K, V> getThreadLocal() {
    if(threadLocal.get() == null) {
      threadLocal.set(new HashMap<>());
    }
    return (Map<K, V>) threadLocal.get();
  }

  public void reset() {
    threadLocal.remove();
  }

  public V get(K key) {
    return getThreadLocal().get(key);
  }

  public void put(K key, V value) {
    getThreadLocal().put(key, value);
  }
}
