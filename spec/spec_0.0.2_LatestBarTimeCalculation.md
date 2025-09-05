# Trading System Specification v0.0.2: Bar Time Calculation
# Trading System Specification v0.0.2: Bar Time Calculation

## Overview

This document extends the Trading System Specification v0.0.1 by providing detailed requirements for the calculation of bar end times. The accurate calculation of bar end times is crucial for properly categorizing market ticks into their respective time intervals (candles).

## Design Decision: Dedicated Time Calculator Class

To follow the Single Responsibility Principle, we will create a dedicated class called `BarTimeCalculator` to handle all time-related calculations for market data bars. This will:

1. Decouple time calculation logic from the `LatestBarSeriesProvider` class
2. Make the time calculations more testable and maintainable
3. Allow for easier expansion of time-related features in the future
4. Promote code reuse across the application

## Current Status

As of the current implementation:

1. The `calculateBarEndTime` method in `LatestBarSeriesProvider` is a placeholder that returns `null`
2. The `updateBarValues` method is also a placeholder without implementation
3. We need to implement proper time-based calculations for various intervals
4. There is currently no dedicated class for time calculations

## BarTimeCalculator Class Specification

### Class Definition

### Configuration Options

To make the implementation flexible for different markets, the following configuration options should be supported:

1. **Market-specific Time Configuration**
   - Support for different market open/close times based on exchange
   - Configuration for special trading sessions

2. **Market Calendar Integration**
   - Interface for market holiday calendars
   - Support for custom trading day definitions

### Core Methods

1. **calculateBarEndTime** - Determines the end time of a bar based on tick time and interval
2. **calculateBarStartTime** - Determines the start time of a bar based on tick time and interval
3. **isBarComplete** - Checks if a bar is complete based on current time and bar end time
4. **getBarDuration** - Returns the duration of a bar in milliseconds based on interval

### Location

The `BarTimeCalculator` class should be placed in the `com.dtech.algo.runner.candle` package to align with related classes like `LatestBarSeriesProvider` and `DataTick`.

## Bar End Time Calculation Requirements

### Core Functionality

The `calculateBarEndTime` method must determine the end time of a bar (candle) based on the tick timestamp and the specified interval. This calculation must follow financial market conventions for time-based aggregation.

### Interval-Specific Logic

For each interval type, the calculation should follow these market-specific rules:

1. **One Minute (1M)**
   - Ceiling the tick time to the next minute boundary
   - Example: A tick at 10:45:30 belongs to the bar ending at 10:46:00

2. **Five Minutes (5M)**
   - Ceiling the tick time to the next 5-minute boundary (00, 05, 10, 15, etc.)
   - Example: A tick at 10:47:20 belongs to the bar ending at 10:50:00

3. **Fifteen Minutes (15M)**
   - Ceiling the tick time to the next 15-minute boundary (00, 15, 30, 45)
   - Example: A tick at 10:22:10 belongs to the bar ending at 10:30:00

4. **Thirty Minutes (30M)**
   - Ceiling the tick time to the next 30-minute boundary (00, 30)
   - Example: A tick at 10:40:05 belongs to the bar ending at 11:00:00

5. **Hourly (1H)**
   - Ceiling the tick time to the next hour boundary
   - Example: A tick at 10:25:15 belongs to the bar ending at 11:00:00

6. **Daily (1D)**
   - For Indian stock market (NSE/BSE) specific implementation
   - Daily bars should end at 3:30 PM (15:30) market closing time, not midnight
   - Ceiling to the current day's market close (3:30 PM)
   - Example: A tick at 2023-05-15 14:20:30 belongs to the bar ending at 2023-05-15 15:30:00

7. **Weekly (1W)**
   - For Indian stock market (NSE/BSE) specific implementation
   - Weekly bars should end at 3:30 PM (15:30) on Friday (market closing time for the week)
   - Ceiling to the week's end (Friday 3:30 PM)
   - Example: A tick on Wednesday 2023-05-15 14:20:30 belongs to the bar ending at Friday 2023-05-17 15:30:00

### Implementation Considerations

1. **Time Zone Handling**
   - All calculations must preserve the original time zone of the input timestamp
   - Market-specific time zones should be configurable when necessary

2. **Market Hours**
   - For future enhancement: Add support for market hours constraints
   - Daily bars may need to align with market open/close times rather than midnight

3. **Performance Optimization**
   - The calculation must be efficient since it will be called for every market tick
   - Consider caching strategies for repeated calculations within the same time window

4. **Edge Cases**
   - Handle after-hours or pre-market ticks appropriately
   - Account for weekend and holiday gaps in daily bars
   - Handle daylight saving time transitions gracefully

## Code Implementation

### BarTimeCalculator Implementation

The implementation should use Java's `java.time` API with the following approach:

## Bar Value Update Logic

After determining the correct bar end time, the `updateBarValues` method should update the existing bar with new high, low, close prices and volume data from the tick. The implementation should:

1. Access the bar at the specified index in the series
2. Update the high price if the tick price is higher than current high
3. Update the low price if the tick price is lower than current low
4. Always update the close price with the latest tick price
5. Add the tick volume to the current bar volume

The recommended approach is to use the direct field access with reflection as started in the initial implementation, but with proper error handling and logging.

## Testing Strategy

### Unit Tests for `calculateBarEndTime`

1. **Basic Interval Tests**
   - Test calculation for each interval type with a standard timestamp
   - Verify correct rounding and interval end time calculation

2. **Edge Case Tests**
   - Test timestamps exactly at interval boundaries
   - Test with DST transition times
   - Test with different time zones

3. **Error Case Tests**
   - Test with null input
   - Test with invalid intervals

### Integration Tests

1. **Bar Series Update Tests**
   - Test the complete flow from tick processing to bar updates
   - Verify correct bar assignment for ticks at various times
   - Test multiple ticks within the same bar interval
   - Test ticks that span multiple bars

## Enhancement: Enum-Based Time Calculation Strategy

### Design Decision: Adding Safety Mechanisms to Interval Enum

To ensure the system can handle new interval types safely in the future, we will enhance the `Interval` enum with a mechanism that forces developers to properly implement time calculation logic for each interval type. This approach will provide the following benefits:

1. **Compile-Time Safety**: When adding a new interval to the enum, developers will be required to implement necessary time calculation logic, preventing runtime errors.

2. **Self-Enforcing Design**: The design will make it impossible to add a new interval without providing proper time calculation implementation.

3. **Maintainability**: The time calculation logic for each interval will be coupled with its enum definition, making the code more maintainable and reducing the risk of logic being out of sync with interval definitions.

4. **Elimination of Switch-Case Structures**: Removes the need for lengthy switch-case structures in the `BarTimeCalculator` that would otherwise need to be updated when new intervals are added.

5. **Reduced Risk of Errors**: Prevents scenarios where time calculations for new intervals are forgotten or implemented incorrectly.

### Implementation Strategy

The implementation will require enhancing the `Interval` enum to include abstract method(s) that each enum value must implement. This is a pattern that leverages Java's ability to define abstract methods within enums, forcing each enum value to provide specific behavior.

The enhanced enum will include methods such as:

- A method to calculate the end time of a bar based on a tick timestamp
- Potentially methods for calculating start times or other interval-specific calculations

The `BarTimeCalculator` class will then delegate these calculations to the appropriate enum instance rather than containing the logic itself.

## Next Implementation Steps

1. Update the `Interval` enum to include the abstract `calculateBarEndTime` method with implementations for each value
2. Create the `BarTimeCalculator` class in the `com.dtech.algo.runner.candle` package that leverages the enum implementations
3. Implement all methods as described in this specification
4. Create unit tests for both the enhanced `Interval` enum and the `BarTimeCalculator` class
5. Update `LatestBarSeriesProvider` to use `BarTimeCalculator` instead of internal methods
6. Implement the `updateBarValues` method to update bar data with tick information
7. Add tests for the update logic
8. Integrate with the existing tick processing flow

## Dependencies

- Java 21 time API (`java.time.ZonedDateTime`, `java.time.temporal.ChronoUnit`)
- Enhanced Interval enum with abstract method implementation
- IntervalBarSeries interface for bar manipulation

---

*This specification will be used to guide the implementation of the bar time calculation logic in the trading system.*
## Overview

This document extends the Trading System Specification v0.0.1 by providing detailed requirements for the calculation of bar end times in the `LatestBarSeriesProvider` class. The accurate calculation of bar end times is crucial for properly categorizing market ticks into their respective time intervals (candles).

## Current Status

As of the current implementation:

1. The `calculateBarEndTime` method in `LatestBarSeriesProvider` is a placeholder that returns `null`
2. The `updateBarValues` method is also a placeholder without implementation
3. We need to implement proper time-based calculations for various intervals

## Bar End Time Calculation Requirements

### Core Functionality

The `calculateBarEndTime` method in the `BarTimeCalculator` class must determine the end time of a bar (candle) based on the tick timestamp and the specified interval. This calculation must follow financial market conventions for time-based aggregation.

The key operation is a "ceiling" operation that determines the next interval boundary. Unlike rounding (which finds the closest value), ceiling always finds the next interval boundary even if the time is exactly on a boundary.

### Interval-Specific Logic

For each interval type, the calculation should follow these rules:

1. **One Minute (1M)**
   - Round the tick time down to the nearest minute
   - Add 1 minute to get the bar end time
   - Example: A tick at 10:45:30 belongs to the 10:45:00-10:46:00 bar

2. **Five Minutes (5M)**
   - Round the tick time down to the nearest 5-minute mark (00, 05, 10, 15, etc.)
   - Add 5 minutes to get the bar end time
   - Example: A tick at 10:47:20 belongs to the 10:45:00-10:50:00 bar

3. **Fifteen Minutes (15M)**
   - Round the tick time down to the nearest 15-minute mark (00, 15, 30, 45)
   - Add 15 minutes to get the bar end time
   - Example: A tick at 10:22:10 belongs to the 10:15:00-10:30:00 bar

4. **Thirty Minutes (30M)**
   - Round the tick time down to the nearest 30-minute mark (00, 30)
   - Add 30 minutes to get the bar end time
   - Example: A tick at 10:40:05 belongs to the 10:30:00-11:00:00 bar

5. **Hourly (1H)**
   - Round the tick time down to the nearest hour
   - Add 1 hour to get the bar end time
   - Example: A tick at 10:25:15 belongs to the 10:00:00-11:00:00 bar

6. **Daily (1D)**
   - Round the tick time down to the start of the day (midnight)
   - Add 1 day to get the bar end time
   - Example: A tick at 2023-05-15 14:20:30 belongs to the 2023-05-15 00:00:00-2023-05-16 00:00:00 bar

### Implementation Considerations

1. **Time Zone Handling**
   - All calculations must preserve the original time zone of the input timestamp
   - Market-specific time zones should be configurable when necessary

2. **Market Hours**
   - Indian stock market (NSE/BSE) specific implementation:
     - Regular market hours: 9:15 AM to 3:30 PM IST
     - Daily bars: End at 3:30 PM on the same day
     - Weekly bars: End at 3:30 PM on Friday
   - Handle pre-market and post-market sessions appropriately
   - Consider special market sessions (e.g., Muhurat trading during Diwali)

3. **Market Holidays**
   - Consider implementing a `MarketCalendar` class to handle market holidays
   - For daily bars that include market holidays, extend the bar to the next trading day
   - For weekly bars that end on holidays, adjust the end time to the last trading day of the week

3. **Performance Optimization**
   - The calculation must be efficient since it will be called for every market tick
   - Consider caching strategies for repeated calculations within the same time window

4. **Edge Cases**
   - Handle after-hours or pre-market ticks appropriately
   - Account for weekend and holiday gaps in daily bars
   - Handle daylight saving time transitions gracefully

## Code Implementation

The implementation should use Java's `java.time` API with the following approach:

## Integration with LatestBarSeriesProvider

The `LatestBarSeriesProvider` class should be updated to use the `BarTimeCalculator` as follows:

1. Inject the `BarTimeCalculator` as a dependency:

2. Use the calculator in the `updateBarSeries` method:
## Bar Value Update Logic

After determining the correct bar end time, the `updateBarValues` method should update the existing bar with new high, low, close prices and volume data from the tick. The implementation should:

1. Access the bar at the specified index in the series
2. Update the high price if the tick price is higher than current high
3. Update the low price if the tick price is lower than current low
4. Always update the close price with the latest tick price
5. Add the tick volume to the current bar volume

The recommended approach is to use the direct field access with reflection as started in the initial implementation, but with proper error handling and logging.

## BarSeriesHelper Implementation

### Design Decision: Centralized Tick Processing

To improve the system's architecture and performance, we will make `BarSeriesHelper` the central coordinator for tick processing. This approach addresses several key issues:

1. **Separation of Concerns**
   - `LatestBarSeriesProvider` will focus solely on bar series management and caching
   - `BarSeriesHelper` will handle the business logic of processing ticks and updating bars

2. **Improved Caching Efficiency**
   - By loading bar series outside of the update method, we prevent potential cache issues
   - This allows for better memory management and reduced redundant operations

3. **Better Return Value Semantics**
   - Instead of returning boolean flags, we return the actual updated bar object when a new bar is created
   - This provides more meaningful information to the caller and simplifies the handling of completed bars

4. **Enhanced Encapsulation of Bar Update Logic**
   - Move the bar update logic from `updateBarSeries` to a dedicated `updateBarValues` method
   - Make `updateBarValues` accept a `DataTick` parameter to encapsulate all update logic
   - This improves maintainability and reusability of the bar update code

### Key Method Signatures

1. **In LatestBarSeriesProvider**
   ```
   // Change method signature to accept pre-loaded bar series
   // Return the bar object if a new bar is created, null otherwise
   public Bar updateBarSeries(DataTick tick, IntervalBarSeries barSeries)

   // Updated method to take DataTick instead of individual values
   private void updateBarValues(IntervalBarSeries barSeries, int index, DataTick tick)
   ```

2. **In BarSeriesHelper**
   ```
   // Process a tick by finding the right bar series and updating it
   public void processTick(DataTick tick)
   ```

### Implementation Flow

1. `KiteTickerService` receives ticks and passes them to `BarSeriesHelper.processTick(tick)`

2. `BarSeriesHelper` then:
   - Looks up the appropriate config for the instrument in the tick
   - Loads the bar series using `LatestBarSeriesProvider.loadBarSeries(config)`
   - Calls `LatestBarSeriesProvider.updateBarSeries(tick, barSeries)`
   - If a non-null bar is returned (new bar created), adds it to the database update queue

### Thread Safety Considerations

- The `BarSeriesHelper` methods must be thread-safe as they will be called from multiple threads
- Synchronization should be used where appropriate, especially when accessing shared data structures
- Consider using concurrent collections for instrument token to config mappings

## Testing Strategy

### Unit Tests for BarTimeCalculator

1. **Basic Interval Tests**
   - Test calculation for each interval type with a standard timestamp
   - Verify correct rounding and interval end time calculation

2. **Market-Specific Time Tests**
   - Test daily bar calculations to ensure they end at 3:30 PM
   - Test weekly bar calculations to ensure they end at 3:30 PM on Friday
   - Verify that daily bar start times are set to 9:15 AM
   - Test behavior with timestamps from different parts of the trading day

3. **Edge Case Tests**
   - Test timestamps exactly at interval boundaries
   - Test timestamps at market open (9:15 AM) and close (3:30 PM)
   - Test timestamps outside market hours
   - Test with DST transition times
   - Test with different time zones
   - Test weekly bar calculation with timestamps on Friday after market close
   - Test weekly bar calculation with timestamps on weekends

4. **Error Case Tests**
   - Test with null input
   - Test with invalid intervals

### Unit Tests for BarSeriesHelper

1. **Processing Flow Tests**
   - Test the complete tick processing flow
   - Verify correct delegation to LatestBarSeriesProvider
   - Test handling of the returned bar object (null vs. non-null)
   - Verify proper queuing of completed bars

2. **Config Lookup Tests**
   - Test instrument token to config mapping
   - Verify caching of mappings
   - Test handling of unknown instruments

3. **Error Handling Tests**
   - Test recovery from exceptions in various parts of the flow
   - Verify proper logging of errors
   - Test thread safety with concurrent tick processing

### Integration Tests

1. **Bar Series Update Tests**
   - Test the complete flow from tick processing to bar updates
   - Verify correct bar assignment for ticks at various times
   - Test multiple ticks within the same bar interval
   - Test ticks that span multiple bars
   - Verify that completed bars are correctly added to the database update queue

## Next Implementation Steps

1. Implement the `calculateBarEndTime` method following the logic described above
2. Add comprehensive unit tests for the time calculation logic
3. Implement the `updateBarValues` method to update bar data with tick information
4. Add tests for the update logic
5. Integrate with the existing tick processing flow

## Dependencies

- Java 21 time API (`java.time.ZonedDateTime`, `java.time.temporal.ChronoUnit`)
- Interval enum from the existing codebase
- IntervalBarSeries interface for bar manipulation
- Spring framework for dependency injection and component scanning

---

*This specification will be used to guide the implementation of the bar time calculation logic in the trading system.*
