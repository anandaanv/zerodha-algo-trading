package com.dtech.algo.screener.resolver;

import com.dtech.algo.series.InstrumentType;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Real implementation of OptionSymbolResolver that queries the instrument database
 * to find actual CE/PE/FUT instruments based on the underlying symbol.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RealOptionSymbolResolver implements OptionSymbolResolver {

    private final InstrumentRepository instrumentRepository;

    @Override
    public String resolveOption(String underlying, OptionNomination nomination, Double ltp) {
        if (ltp == null || ltp <= 0) {
            log.warn("Invalid LTP {} for {}, cannot resolve option", ltp, underlying);
            return underlying + "_" + nomination.toString();
        }

        InstrumentType type = nomination.getType();
        int offset = nomination.getOffset();

        // Query instruments with name = underlying, type = CE or PE, expiry within next month
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oneMonthLater = now.plusMonths(1);

        List<Instrument> instruments = instrumentRepository.findAllByNameAndInstrumentTypeAndExpiryBetween(
                underlying,
                type.name(),
                now,
                oneMonthLater
        );

        if (instruments.isEmpty()) {
            log.warn("No {} options found for {} with expiry in next month", type, underlying);
            return underlying + "_" + nomination.toString();
        }

        // Partition: true = strikes < ltp, false = strikes >= ltp
        Map<Boolean, List<Instrument>> strikesList = instruments.stream()
                .filter(i -> i.getStrike() != null)
                .sorted(Comparator.comparing(i -> parseStrike(i.getStrike())))
                .collect(Collectors.partitioningBy(i -> parseStrike(i.getStrike()) < ltp));

        List<Instrument> strikesBelow = strikesList.get(true);   // strikes < ltp
        List<Instrument> strikesAbove = strikesList.get(false);  // strikes >= ltp

        List<Instrument> targetList;

        if (type == InstrumentType.CE) {
            if (offset > 0) {
                // CE + positive = OTM calls = strikes above LTP, ascending order (closest first)
                targetList = strikesAbove;
            } else {
                // CE + negative = ITM calls = strikes below LTP, descending order (closest first)
                targetList = strikesBelow.reversed();
            }
        } else { // PE
            if (offset > 0) {
                // PE + positive = OTM puts = strikes below LTP, descending order (closest first)
                targetList = strikesBelow.reversed();
            } else {
                // PE + negative = ITM puts = strikes above LTP, ascending order (closest first)
                targetList = strikesAbove;
            }
        }

        // Convert offset to 0-based index
        int index = Math.abs(offset) - 1;

        if (index < 0 || index >= targetList.size()) {
            log.warn("Target index {} out of bounds for {} {} offset {}", index, type, underlying, offset);
            return underlying + "_" + nomination.toString();
        }

        return targetList.get(index).getTradingsymbol();
    }

    @Override
    public String resolveFuture(String underlying) {
        // Query instruments with name = underlying, type = FUT, expiry in current month
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endOfMonth = now.withDayOfMonth(now.toLocalDate().lengthOfMonth()).withHour(23).withMinute(59);

        List<Instrument> futures = instrumentRepository.findAllByNameAndInstrumentTypeAndExpiryBetween(
                underlying,
                "FUT",
                now,
                endOfMonth
        );

        if (futures.isEmpty()) {
            log.warn("No futures found for {} with expiry in current month", underlying);
            return underlying + "_FUT";
        }

        // Return the nearest future (first by expiry)
        return futures.stream()
                .filter(f -> f.getExpiry() != null)
                .min(Comparator.comparing(Instrument::getExpiry))
                .map(Instrument::getTradingsymbol)
                .orElse(underlying + "_FUT");
    }

    private double parseStrike(String strike) {
        try {
            return Double.parseDouble(strike);
        } catch (Exception e) {
            return 0.0;
        }
    }
}
