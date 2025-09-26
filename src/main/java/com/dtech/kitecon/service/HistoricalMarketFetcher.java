package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.config.HistoricalDateLimit;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.market.fetch.ZerodhaDataFetch;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Wrapper around ZerodhaDataFetch that enforces a rate limit (requests per second)
 * and persists (rewrites) candles for an instrument/timeframe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HistoricalMarketFetcher {

  private final ZerodhaDataFetch zerodhaDataFetch;
  private final DataFetchService dataFetchService;
  private final CandleRepository candleRepository;
  private final HistoricalDateLimit historicalDateLimit;

  // rate limiter (permits per second)
  private final RateLimiter rateLimiter = RateLimiter.create(3.0); // default; can be overwritten in constructor if needed

  @Value("${data.update.rateLimitPerSecond:3.0}")
  private double configuredRate;

  @Value("${data.update.fetchRetries:3}")
  private int maxRetries;

  /**
   * Acquire permit and fetch candles for given instrument and interval starting from `start` (inclusive).
   * Returns the latest Instant saved (if any). Retries on transient errors with exponential backoff.
   */
  @Transactional
  public java.util.Optional<java.time.Instant> fetchAndPersist(Instrument instrument, Interval interval, java.time.Instant start) throws InterruptedException {
    // Apply configured rate if changed (safe to set every call)
    if (configuredRate > 0 && configuredRate != rateLimiter.getRate()) {
      rateLimiter.setRate(configuredRate);
    }

    int attempt = 0;
    while (attempt < maxRetries) {
      attempt++;
      // Acquire a single permit before each external call
      rateLimiter.acquire();

      try {
          if (start == null) {
              dataFetchService.updateInstrument(instrument, interval, true);
          } else {
              int sliceSize = historicalDateLimit.getDuration(instrument.getExchange(), interval);
              dataFetchService.fetchDataAndUpdateDatabase(instrument, interval, Instant.now(), sliceSize, start, false);
          }
          return Optional.ofNullable(Instant.now());
      } catch (Throwable t) {
        log.warn("Attempt {}: failed to fetch/persist for {} {} : {}", attempt, instrument.getTradingsymbol(), interval, t.getMessage());
        if (attempt >= maxRetries) {
          log.error("Max retries reached for {} {}. Giving up for this run.", instrument.getTradingsymbol(), interval, t);
          return java.util.Optional.empty();
        } else {
          // exponential backoff (ms)
          long backoffMillis = (long) (500L * Math.pow(2, attempt - 1));
          Thread.sleep(backoffMillis);
        }
      }
    }
    return java.util.Optional.empty();
  }
}
