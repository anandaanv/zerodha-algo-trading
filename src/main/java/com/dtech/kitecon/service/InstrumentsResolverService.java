package com.dtech.kitecon.service;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.runner.candle.LatestBarSeriesProvider;
import com.dtech.kitecon.controller.BarSeriesHelper;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.fetch.DataFetchException;
import com.dtech.kitecon.market.fetch.MarketDataFetch;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves NFO instruments (futures/options) for a given NSE underlying trading symbol.
 * Uses InstrumentRepository queries and the instrument.strike field to pick options.
 */
@Service
@RequiredArgsConstructor
public class InstrumentsResolverService {

  private final InstrumentRepository instrumentRepository;
    private final BarSeriesHelper barSeriesHelper;
    private final MarketDataFetch marketDataFetch;

    private static final String NFO_EXCHANGE = "NFO";
  private static final String FUT_TYPE = "FUT";
  private static final String OPT_TYPE = "OPT";

  /**
   * Resolve nearest futures (by expiry ascending) for given underlying prefix.
   * Returns up to 'limit' futures (usually 2).
   */
  public List<Instrument> resolveNearestFutures(String underlyingTradingSymbolPrefix, int limit) {
    List<Instrument> candidates = instrumentRepository
        .findAllByExchangeAndInstrumentTypeAndTradingsymbolStartingWith(NFO_EXCHANGE, FUT_TYPE, underlyingTradingSymbolPrefix);
    return candidates.stream()
        .filter(i -> i.getExpiry() != null && i.getExpiry().isAfter(LocalDateTime.now()))
        .sorted(Comparator.comparing(Instrument::getExpiry))
        .limit(limit)
        .collect(Collectors.toList());
  }

  /**
   * Resolve options for the current series and pick up to 'limit' strikes closest to ATM.
   * Uses instrument.strike field to compute closeness. If lastPrice not available on cash instrument,
   * tries to infer ATM by scanning strikes.
   */
  @SneakyThrows
  public List<Instrument> resolveTopOptions(String underlyingTradingSymbolPrefix, Instrument underlyingCash, int limit) {
    List<Instrument> opts = instrumentRepository
        .findAllByExchangeAndInstrumentTypeAndTradingsymbolStartingWith(NFO_EXCHANGE, "CE", underlyingTradingSymbolPrefix);
      opts.addAll(instrumentRepository
              .findAllByExchangeAndInstrumentTypeAndTradingsymbolStartingWith(NFO_EXCHANGE, "PE", underlyingTradingSymbolPrefix));

    if (opts == null || opts.isEmpty()) return Collections.emptyList();

    // Group options by expiry (series). We'll pick the nearest expiry (smallest non-null expiry).
    LocalDateTime nearestExpiry = opts.stream()
        .map(Instrument::getExpiry)
        .filter(Objects::nonNull)
            .filter(e -> e.isAfter(LocalDateTime.now()))
        .min(LocalDateTime::compareTo)
        .orElse(null);

    final LocalDateTime targetExpiry = nearestExpiry;

    // Filter to current series if we found expiry otherwise keep all
    List<Instrument> seriesOptions = opts.stream()
        .filter(i -> targetExpiry == null || targetExpiry.equals(i.getExpiry()))
        .collect(Collectors.toList());

    // Determine ATM strike using underlyingCash.lastPrice if present, otherwise approximate using strike list
    double atm = Double.NaN;
      if (underlyingCash != null) {
          Double lastPrice = getLastPrice(underlyingCash);
          if (lastPrice != null) {
              atm = lastPrice;
          }
      } else {
      // fallback: choose median strike if parsing possible
      List<Double> strikes = seriesOptions.stream()
          .map(Instrument::getStrike)
          .filter(Objects::nonNull)
          .map(s -> {
            try {
              return Double.parseDouble(s);
            } catch (Exception e) {
              return null;
            }
          })
          .filter(Objects::nonNull)
          .sorted()
          .collect(Collectors.toList());
      if (!strikes.isEmpty()) {
        atm = strikes.get(strikes.size() / 2);
      }
    }

    final double atmFinal = atm;

    // Sort by absolute distance from ATM. If ATM not available, fallback to sort by strike numeric asc.
    List<Instrument> sorted = seriesOptions.stream()
        .filter(i -> i.getStrike() != null)
        .sorted((a, b) -> {
          Double sa = safeParseStrike(a.getStrike());
          Double sb = safeParseStrike(b.getStrike());
          if (sa == null && sb == null) return 0;
          if (sa == null) return 1;
          if (sb == null) return -1;
          if (!Double.isNaN(atmFinal)) {
            double da = Math.abs(sa - atmFinal);
            double db = Math.abs(sb - atmFinal);
            return Double.compare(da, db);
          } else {
            return Double.compare(sa, sb);
          }
        })
        .limit(limit)
        .collect(Collectors.toList());

    return sorted;
  }

    private Double getLastPrice(Instrument instrument) throws StrategyException {
        try {
            return marketDataFetch.getLastPrice(instrument);
        } catch (DataFetchException e) {
            return barSeriesHelper.getLastPrice(instrument);
        }
    }

    private static Double safeParseStrike(String s) {
    if (s == null) return null;
    try {
      return Double.parseDouble(s);
    } catch (Exception e) {
      return null;
    }
  }
}
