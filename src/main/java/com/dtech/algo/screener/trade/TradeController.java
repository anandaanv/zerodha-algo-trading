package com.dtech.algo.screener.trade;

import com.dtech.algo.series.Interval;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeController {

    private final IdentifiedTradeRepository repository;

    @GetMapping
    public List<IdentifiedTrade> listTrades(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Boolean open,
            @RequestParam(required = false) String script,
            @RequestParam(required = false) String timeframe,
            @RequestParam(required = false) String side,
            @RequestParam(required = false) String status
    ) {
        // If open not specified and status=ACTIVE, default open=true
        Boolean openEffective = open;
        if (openEffective == null && status != null && "ACTIVE".equalsIgnoreCase(status)) {
            openEffective = Boolean.TRUE;
        }

        Specification<IdentifiedTrade> spec = Specification.where(null);

        if (from != null) {
            spec = spec.and((root, cq, cb) -> cb.greaterThanOrEqualTo(root.get("timeTriggered"), from));
        }
        if (to != null) {
            spec = spec.and((root, cq, cb) -> cb.lessThanOrEqualTo(root.get("timeTriggered"), to));
        }
        if (openEffective != null) {
            final boolean isOpen = openEffective;
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("open"), isOpen));
        }
        if (script != null && !script.isBlank()) {
            final String value = script.trim();
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("script"), value));
        }
        if (side != null && !side.isBlank()) {
            final String value = side.trim().toUpperCase(Locale.ROOT);
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("side"), value));
        }
        if (timeframe != null && !timeframe.isBlank()) {
            try {
                Interval tf = Interval.valueOf(timeframe.trim().toUpperCase(Locale.ROOT));
                spec = spec.and((root, cq, cb) -> cb.equal(root.get("timeframe"), tf));
            } catch (IllegalArgumentException ignored) {
                // unknown timeframe value; ignore filter
            }
        }

        return repository.findAll(spec, Sort.by(Sort.Direction.DESC, "timeTriggered"));
    }

    @GetMapping("/{id}")
    public IdentifiedTrade getTrade(@PathVariable Long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Trade not found: " + id));
    }
}
