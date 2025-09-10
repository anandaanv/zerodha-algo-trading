package com.dtech.algo.series;

import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import lombok.*;
import lombok.experimental.Delegate;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.bars.TimeBarBuilder;
import org.ta4j.core.bars.TimeBarBuilderFactory;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class ExtendedBarSeries implements IntervalBarSeries {

    @Delegate(types = BarSeries.class)
    private BarSeries delegate;

    private Interval interval;
    private SeriesType seriesType;
    private String instrument;

    @Override
    public void addBarWithTimeValidation(Instant endTime, Number openPrice, Number highPrice, Number lowPrice, Number closePrice,
                                         Number volume) {
        // FIXME - we don't have implementation that covers week and months as of now..
        //  Algotrading doens't really deal in that range.
        endTime = calculateActualEndTime(endTime);
        boolean replace = false;
        if(this.getBarCount() > 0 && endTime.equals(getLastBar().getEndTime())) {
            replace = true;
        }

        BaseBar bar = new BaseBar(Duration.ofDays(1), endTime, numOf(openPrice), numOf(highPrice), numOf(lowPrice),
                numOf(closePrice), numOf(volume), numOf(0), 0);
//        if(replace == false && this.getBarCount() > 0 && syncExecutor != null) {
//            syncExecutor.submit(new CandleSyncToken(bar, instrument, interval));
//        }
        this.addBar(bar, replace);
    }
    private Instant calculateActualEndTime(Instant endTime) {
        int timeMinute = endTime.get(ChronoField.MINUTE_OF_HOUR);
        int timeHour = endTime.get(ChronoField.HOUR_OF_DAY);
        int offset = getOffset(interval);
        if (interval == Interval.OneHour || interval == Interval.FourHours) {
            endTime = endTime.truncatedTo(ChronoUnit.DAYS)
                    .plus(((timeHour / offset) + 1) * offset, ChronoUnit.HOURS);
        } else {
            endTime = endTime.truncatedTo(ChronoUnit.HOURS)
                    .plus(((timeMinute / offset) + 1) * offset, ChronoUnit.MINUTES);
        }
        return endTime;
    }

    private int getOffset(Interval interval) {
        switch (interval) {
            case OneMinute:
            case OneHour:
                return 1;
            case ThreeMinute:
                return 3;
            case FiveMinute:
                return 5;
            case FifteenMinute:
                return 15;
            case ThirtyMinute:
                return 30;
            case FourHours:
                return 4;
            default:
                return 0;
        }
    }
}
