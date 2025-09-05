package com.dtech.algo.runner.candle;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Expected to cache bar series from the request, The request will be similar to that comes for strategy.
 */
@RequiredArgsConstructor
@Component
@Log4j2
public class LatestBarSeriesProvider implements UpdatableBarSeriesLoader {

    private final BarSeriesLoader delegate;
    private final BarTimeCalculator barTimeCalculator;

    private final Map<String, IntervalBarSeries> cache = new WeakHashMap<>();

    public IntervalBarSeries loadBarSeries(BarSeriesConfig barSeriesConfig) throws StrategyException {
        try {
            String key = barSeriesConfig.getInstrument() + barSeriesConfig.getSeriesType() + barSeriesConfig.getInterval();
            if(cache.containsKey(key)) {
                return cache.get(key);
            } else {
                BarSeriesConfig refSeries = barSeriesConfig.clone();
                refSeries.setStartDate(refSeries.getStartDate());
                refSeries.setEndDate(refSeries.getEndDate());
                IntervalBarSeries series = delegate.loadBarSeries(refSeries);
                cache.put(key, series);
                return series;
            }
        } catch (CloneNotSupportedException e) {
            throw new StrategyException(e);
        }
    }
    /**
     * Updates a bar series with a new tick
     * 
     * @param tick The market data tick
     * @param barSeries The pre-loaded bar series to update
     * @return The last completed bar if a new bar was created, null otherwise
     */
    public Bar updateBarSeries(DataTick tick, IntervalBarSeries barSeries) {
        try {
            if (barSeries == null) {
                log.warn("Received null bar series in updateBarSeries");
                return null;
            }

            // Get the current bar's end time based on tick timestamp and interval
            ZonedDateTime tickTime = tick.getTickTimestamp() != null ? 
                ZonedDateTime.ofInstant(tick.getTickTimestamp().toInstant(), ZonedDateTime.now().getZone()) : 
                ZonedDateTime.now();
            ZonedDateTime barEndTime = barTimeCalculator.calculateBarEndTime(tickTime, barSeries.getInterval());

            // Check if we're still in the current bar or need a new one
            int lastBarIndex = barSeries.getEndIndex();
            boolean isNewBar = false;
            Bar completedBar = null;

            if (lastBarIndex >= 0) {
                Bar lastBar = barSeries.getBar(lastBarIndex);
                ZonedDateTime lastBarTime = lastBar.getEndTime();
                isNewBar = barEndTime.isAfter(lastBarTime);

                // Cache the completed bar before creating a new one
                if (isNewBar) {
                    completedBar = lastBar;
                }
            }

            if (isNewBar) {
                // If this is a new bar, add it to the series
                log.debug("Creating new bar for interval {}, time: {}", barSeries.getInterval(), barEndTime);

                // We need to create a new bar
                Number price = tick.getLastTradedPrice();
                Number volume = tick.getVolumeTradedToday();

                barSeries.addBarWithTimeValidation(
                    barEndTime,   // endTime
                    price,         // openPrice
                    price,         // highPrice
                    price,         // lowPrice
                    price,         // closePrice
                    volume         // volume
                );

                // Return the completed (previous) bar
                return completedBar;
            } else {
                // Update the current bar with the tick data
                updateBarValues(barSeries, lastBarIndex, tick);

                // Return null to indicate no new bar was created
                return null;
            }
        } catch (Exception e) {
            log.error("Error updating bar series for tick: {}", tick, e);
            return null;
        }
    }

    /**
     * Updates the values of an existing bar with data from a tick
     * 
     * @param barSeries The bar series containing the bar to update
     * @param index The index of the bar to update
     * @param tick The market data tick containing the updated values
     */
    private void updateBarValues(IntervalBarSeries barSeries, int index, DataTick tick) {
        try {
            Bar currentBar = barSeries.getBar(index);
            if (currentBar == null) {
                log.warn("No bar found at index {} in bar series {}", index, barSeries.getName());
                return;
            }

            // Extract values from the tick
            double tickPrice = tick.getLastTradedPrice();
            double tickVolume = tick.getVolumeTradedToday();

            // Get current values from the bar
            double currentHigh = currentBar.getHighPrice().doubleValue();
            double currentLow = currentBar.getLowPrice().doubleValue();
            double currentVolume = currentBar.getVolume().doubleValue();

            // Calculate new values
            double newHigh = Math.max(tickPrice, currentHigh);
            double newLow = Math.min(tickPrice, currentLow);
            double newVolume = currentVolume + tickVolume;

            // Access the underlying BaseBar implementation
            if (currentBar instanceof org.ta4j.core.BaseBar) {
                org.ta4j.core.BaseBar baseBar = (org.ta4j.core.BaseBar) currentBar;

                // Update the bar values using direct setters
                baseBar.addPrice(barSeries.numOf(tickPrice));

                try {
                    // Access the private volume field using reflection
                    Field volumeField = org.ta4j.core.BaseBar.class.getDeclaredField("volume");
                    volumeField.setAccessible(true);
                    Object currentVolumeObj = volumeField.get(baseBar);

                    // Add the new volume to the current volume
                    if (currentVolumeObj != null) {
                        // Assuming the volume field is a Num type that has a plus method
                        Method plusMethod = currentVolumeObj.getClass().getMethod("plus", currentVolumeObj.getClass().getInterfaces()[0]);
                        Object result = plusMethod.invoke(currentVolumeObj, barSeries.numOf(tickVolume));
                        volumeField.set(baseBar, result);
                    }
                } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                    log.error("Error updating volume using reflection: {}", e.getMessage(), e);
                }

                log.debug("Updated bar at index {} with high: {}, low: {}, close: {}, volume: {}", 
                          index, newHigh, newLow, tickPrice, newVolume);
            } else {
                // If not a BaseBar, fall back to using barSeries.addBar to replace the current bar
                ZonedDateTime endTime = currentBar.getEndTime();
                Number openPrice = currentBar.getOpenPrice().doubleValue();

                // Remove the current bar
                // Note: This might not be supported by all BarSeries implementations
                // If not supported, would need to create a new series with the updated bar

                // Add back the bar with updated values
                barSeries.addBarWithTimeValidation(
                    endTime,
                    openPrice,
                    newHigh,
                    newLow,
                    tickPrice,
                    newVolume
                );

                log.debug("Replaced bar at index {} with high: {}, low: {}, close: {}, volume: {}", 
                          index, newHigh, newLow, tickPrice, newVolume);
            }
        } catch (Exception e) {
            log.error("Error in updateBarValues: {}", e.getMessage(), e);
        }
    }
}
