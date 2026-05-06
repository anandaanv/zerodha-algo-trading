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
        return resolve(symbol, segment, direction, underlyingLtp, 0.0);
    }

    public ResolvedInstrument resolve(String symbol, TradingSegment segment,
                                     TradeDirection direction, BigDecimal underlyingLtp, double otmPct) {
        if (segment == TradingSegment.EQ) {
            return resolveEQ(symbol);
        } else if (segment == TradingSegment.FUT) {
            return resolveFUT(symbol);
        } else {
            return resolveOPT(symbol, direction, underlyingLtp, otmPct);
        }
    }

    private ResolvedInstrument resolveEQ(String symbol) {
        List<Instrument> instruments = instrumentRepository.findAllByTradingsymbolAndExchangeIn(
                symbol, new String[]{"BSE", "NSE"});
        if (instruments.isEmpty()) {
            throw new RuntimeException("EQ instrument not found: " + symbol);
        }
        // Prefer BSE over NSE (NSE may be disabled on API key)
        Instrument inst = instruments.stream()
                .filter(i -> "BSE".equals(i.getExchange()))
                .findFirst()
                .orElse(instruments.get(0));
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

    /**
     * Resolve an OTM option instrument.
     * @param otmPct how far OTM (0.0 = ATM, 0.03 = 3% OTM)
     */
    public ResolvedInstrument resolveOTMOption(String symbol, TradeDirection direction, BigDecimal underlyingLtp, double otmPct) {
        return resolveOPT(symbol, direction, underlyingLtp, otmPct);
    }

    private ResolvedInstrument resolveOPT(String symbol, TradeDirection direction, BigDecimal underlyingLtp) {
        return resolveOPT(symbol, direction, underlyingLtp, 0.0);
    }

    private ResolvedInstrument resolveOPT(String symbol, TradeDirection direction, BigDecimal underlyingLtp, double otmPct) {
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
        // Expiry week check: use ATM if expiry is within 5 days (OTM has no value)
        boolean expiryWeek = nearestExpiry.isBefore(now.plusDays(5));
        double effectiveOtmPct = expiryWeek ? 0.0 : otmPct;
        if (expiryWeek && otmPct > 0) {
            log.info("[InstrumentResolver] {} expiry week ({}) — using ATM instead of {}% OTM",
                    symbol, nearestExpiry.toLocalDate(), otmPct * 100);
        }

        // Default to 1-OTM strike (the nearest strike on the OTM side of LTP):
        //   LONG  / CE → first strike >= LTP
        //   SHORT / PE → first strike <= LTP
        // Picking the absolute-closest strike can land ITM and load the option with
        // intrinsic value that destroys risk:reward.
        final boolean otmHigher = direction == TradeDirection.LONG;
        Instrument atm = options.stream()
                .filter(i -> nearestExpiry.equals(i.getExpiry()))
                .filter(i -> i.getStrike() != null)
                .filter(i -> {
                    double s = Double.parseDouble(i.getStrike());
                    if (effectiveOtmPct > 0) {
                        // Caller asked for explicit OTM offset — strike must be at least that far OTM
                        double minOffset = ltp * effectiveOtmPct;
                        return otmHigher ? s >= ltp + minOffset : s <= ltp - minOffset;
                    }
                    // Default 1-OTM: strike strictly on the OTM side of LTP
                    return otmHigher ? s >= ltp : s <= ltp;
                })
                .min(Comparator.comparingDouble(i -> Math.abs(Double.parseDouble(i.getStrike()) - ltp)))
                .orElseThrow(() -> new RuntimeException("No OTM " + optionType + " option found for: " + symbol
                        + " near LTP " + ltp + " (otmPct=" + effectiveOtmPct + ")"));

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
