package com.dtech.kitecon.web;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@CrossOrigin // adjust origins as needed
public class SymbolController {

    private final InstrumentRepository instrumentRepository;

    /**
     * Search for symbols that start with the provided query string.
     * Only returns "active" instruments: expiry == null OR expiry date after today.
     *
     * Example:
     *   GET /api/symbols?query=HDFCBANK
     */
    @GetMapping("/symbols")
    public List<SymbolInfo> search(@RequestParam(name = "query") @NotBlank String query) {
        String q = query.trim();
        if (q.isEmpty()) return List.of();

        // repository method is case-sensitive depending on DB collation; normalize to uppercase for common symbols
        String qUpper = q.toUpperCase();

        List<Instrument> found = instrumentRepository
                .findAllByTradingsymbolStartingWithAndExchangeIn(qUpper,
                        new String[]{"NSE", "NFO"});

        LocalDate today = LocalDate.now();
        return found.stream()
                .filter(inst -> isActive(inst, today))
                .sorted(Comparator.comparing(Instrument::getLotSize)
                        .thenComparing(Instrument::getName)
                        .thenComparing(Instrument::getInstrumentType)
                        .thenComparing(Instrument::getExpiry))
                .map(inst -> new SymbolInfo(
                        inst.getTradingsymbol(),
                        inst.getName(),
                        inst.getLastPrice(),
                        inst.getExpiry(),
                        inst.getStrike(),
                        inst.getInstrumentType(),
                        inst.getSegment(),
                        inst.getExchange(),
                        inst.getLotSize(),
                        inst.getTickSize()
                ))
                .collect(Collectors.toList());
    }

    private boolean isActive(Instrument inst, LocalDate today) {
        LocalDateTime exp = inst.getExpiry();
        if (exp == null) return true;
        // Active only if expiry date is strictly after today's date
        return exp.toLocalDate().isAfter(today);
    }

    public static record SymbolInfo(
            String tradingsymbol,
            String name,
            Double lastPrice,
            LocalDateTime expiry,
            String strike,
            String instrumentType,
            String segment,
            String exchange,
            Integer lotSize,
            Double tickSize
    ) {}
}
