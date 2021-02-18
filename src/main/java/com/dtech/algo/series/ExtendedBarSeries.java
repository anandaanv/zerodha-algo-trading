package com.dtech.algo.series;

import com.dtech.algo.strategy.sync.CandleSyncExecutor;
import com.dtech.algo.strategy.sync.CandleSyncJob;
import com.dtech.algo.strategy.sync.CandleSyncToken;
import lombok.*;
import lombok.experimental.Delegate;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;

import java.time.Duration;
import java.time.ZonedDateTime;
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

    private CandleSyncExecutor syncExecutor;

    @Override
    public void addBarWithTimeValidation(ZonedDateTime endTime, Number openPrice, Number highPrice, Number lowPrice, Number closePrice,
                                         Number volume) {
        // FIXME - we don't have implementation that covers week and months as of now..
        //  Algotrading doens't really deal in that range.
        int timeMinute = endTime.getMinute();
        int timeHour = endTime.getHour();
        int offset = getOffset(interval);
        if (interval == Interval.OneHour || interval == Interval.FourHours) {
            endTime = endTime.truncatedTo(ChronoUnit.DAYS)
                    .plusHours(((timeHour / offset) + 1) * offset);
        } else {
            endTime = endTime.truncatedTo(ChronoUnit.HOURS)
                    .plusMinutes(((timeMinute / offset) + 1) * offset);
        }
        boolean replace = false;
        if(this.getBarCount() > 0 && endTime.equals(getLastBar().getEndTime())) {
            replace = true;
        }
        BaseBar bar = new BaseBar(Duration.ofDays(1), endTime, numOf(openPrice), numOf(highPrice), numOf(lowPrice),
                numOf(closePrice), numOf(volume), numOf(0));
        if(replace == false && this.getBarCount() > 0) {
            syncExecutor.submit(new CandleSyncToken(bar, instrument, interval));
        }
        this.addBar(bar, replace);
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
