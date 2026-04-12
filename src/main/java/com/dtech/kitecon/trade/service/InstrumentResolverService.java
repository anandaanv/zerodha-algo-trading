package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.dto.ResolvedInstrument;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradingSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class InstrumentResolverService {

    private final InstrumentRepository instrumentRepository;

    public ResolvedInstrument resolve(String symbol, TradingSegment segment,
                                     TradeDirection direction, BigDecimal underlyingLtp) {
        if (segment == TradingSegment.EQ) {
            return resolveEQ(symbol);
        } else if (segment == TradingSegment.FUT) {
            return resolveFUT(symbol);
        } else {
            return resolveOPT(symbol, direction, underlyingLtp);
        }
    }

    private ResolvedInstrument resolveEQ(String symbol) {
        Instrument inst = instrumentRepository.findByTradingsymbolAndExchangeIn(
                symbol, new String[]{"NSE"});
        if (inst == null) {
            throw new RuntimeException("EQ instrument not found: " + symbol);
        }
        return ResolvedInstrument.builder()
                .tradingSymbol(inst.getTradingsymbol())
                .instrumentToken(inst.getInstrumentToken())
                .lotSize(inst.getLotSize() != null ? inst.getLotSize() : 1)
                .instrumentType("EQ")
                .build();
    }

    private ResolvedInstrument resolveFUT(String symbol) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.plusDays(45);

        List<Instrument> futures = instrumentRepository
                .findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
                        symbol, now, cutoff, new String[]{"NFO"});

        futures = futures.stream()
                .filter(i -> "FUT".equals(i.getInstrumentType()))
                .sorted(Comparator.comparing(Instrument::getExpiry))
                .collect(Collectors.toList());

        if (futures.isEmpty()) {
            throw new RuntimeException("No FUT instrument found for: " + symbol);
        }

        Instrument nearest = futures.get(0);
        return ResolvedInstrument.builder()
                .tradingSymbol(nearest.getTradingsymbol())
                .instrumentToken(nearest.getInstrumentToken())
                .lotSize(nearest.getLotSize() != null ? nearest.getLotSize() : 1)
                .instrumentType("FUT")
                .expiry(nearest.getExpiry() != null ? nearest.getExpiry().toLocalDate() : null)
                .build();
    }

    private ResolvedInstrument resolveOPT(String symbol, TradeDirection direction, BigDecimal underlyingLtp) {
        String optionType = direction == TradeDirection.LONG ? "CE" : "PE";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.plusDays(45);

        List<Instrument> options = instrumentRepository
                .findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
                        symbol, now, cutoff, new String[]{"NFO"});

        options = options.stream()
                .filter(i -> optionType.equals(i.getInstrumentType()))
                .collect(Collectors.toList());

        if (options.isEmpty()) {
            throw new RuntimeException("No " + optionType + " options found for: " + symbol);
        }

        LocalDateTime nearestExpiry = options.stream()
                .map(Instrument::getExpiry)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new RuntimeException("No expiry found"));

        double ltp = underlyingLtp.doubleValue();
        Instrument atm = options.stream()
                .filter(i -> nearestExpiry.equals(i.getExpiry()))
                .filter(i -> i.getStrike() != null)
                .min(Comparator.comparingDouble(i -> Math.abs(Double.parseDouble(i.getStrike()) - ltp)))
                .orElseThrow(() -> new RuntimeException("No ATM option found for: " + symbol));

        return ResolvedInstrument.builder()
                .tradingSymbol(atm.getTradingsymbol())
                .instrumentToken(atm.getInstrumentToken())
                .lotSize(atm.getLotSize() != null ? atm.getLotSize() : 1)
                .instrumentType(optionType)
                .expiry(atm.getExpiry() != null ? atm.getExpiry().toLocalDate() : null)
                .strike(atm.getStrike() != null ? new BigDecimal(atm.getStrike()) : null)
                .build();
    }
}
