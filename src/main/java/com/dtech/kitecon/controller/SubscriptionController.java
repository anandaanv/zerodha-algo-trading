package com.dtech.kitecon.controller;

import com.dtech.kitecon.data.Subscription;
import com.dtech.kitecon.repository.IndexSymbolRepository;
import com.dtech.kitecon.repository.SubscriptionRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Controller to manage subscriptions.
 *
 * - POST /api/subscriptions  : add a subscription (single symbol or index)
 *
 * Request body:
 * {
 *   "symbol": "NIFTY50",
 *   "index": true
 * }
 *
 * If index is true, IndexSymbolService will be asked to expand the constituent symbols
 * and subscriptions will be created for each.
 */
@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
@Slf4j
public class SubscriptionController {

  private final SubscriptionRepository subscriptionRepository;
  private final IndexSymbolRepository indexSymbolRepository;

  @PostMapping
  public ResponseEntity<AddSubscriptionResponse> addSubscription(@RequestBody AddSubscriptionRequest req) {
    if (req == null || req.getSymbol() == null || req.getSymbol().isBlank()) {
      return ResponseEntity.badRequest().body(new AddSubscriptionResponse(Collections.emptyList(), Collections.emptyList(), "invalid request"));
    }

    List<String> toCreate = new ArrayList<>();
    if (Boolean.TRUE.equals(req.getIndex())) {
      // expand index into constituent symbols
      List<String> constituents = indexSymbolRepository.findAllSymbolsByIndexName(req.getSymbol());
      if (constituents == null || constituents.isEmpty()) {
        String msg = "No constituents found for index: " + req.getSymbol();
        log.warn(msg);
        return ResponseEntity.badRequest().body(new AddSubscriptionResponse(Collections.emptyList(), Collections.emptyList(), msg));
      }
      toCreate.addAll(constituents);
    } else {
      toCreate.add(req.getSymbol());
    }

    List<String> created = new ArrayList<>();
    List<String> skipped = new ArrayList<>();

    LocalDateTime now = LocalDateTime.now();

    for (String sym : toCreate) {
      try {
        if (subscriptionRepository.existsByTradingSymbol(sym)) {
          skipped.add(sym);
          continue;
        }

        Subscription s = Subscription.builder()
            .tradingSymbol(sym)
            .createdAt(now)
            .lastUpdatedAt(now)
            .latestTimestamp(null)
            .status(Optional.ofNullable(req.getStatus()).orElse("ACTIVE"))
            .metaJson(null)
            .build();

        subscriptionRepository.save(s);
        created.add(sym);
      } catch (Exception ex) {
        log.error("Failed to create subscription for {}: {}", sym, ex.getMessage(), ex);
        skipped.add(sym);
      }
    }

    String message = String.format("created=%d skipped=%d", created.size(), skipped.size());
    AddSubscriptionResponse resp = new AddSubscriptionResponse(created, skipped, message);
    return ResponseEntity.ok(resp);
  }

  @Data
  public static class AddSubscriptionRequest {
    @NotBlank
    private String symbol;
    // whether the provided symbol is an index (true) or a single tradingsymbol (false)
    private Boolean index = false;
    // optional desired status (defaults to ACTIVE)
    private String status;
  }

  @Data
  @AllArgsConstructor
  public static class AddSubscriptionResponse {
    private List<String> created;
    private List<String> skipped;
    private String message;
  }
}
