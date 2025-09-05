# Trading System Specification v0.0.1

## System Overview

This document outlines the specifications for a trading system built with Jakarta EE, Spring Data JPA, and Java 21. The system provides functionality for algorithmic trading with real-time data processing capabilities.

## Current Functionality

Based on the existing codebase, the system currently has:

1. **Bar Series Management**
   - `LatestBarSeriesProvider` for caching and providing time series data
   - `IntervalBarSeries` interface for standardized bar series operations
   - Support for different time intervals (through `Interval` enum)

2. **Data Fetching**
   - REST endpoints for fetching market data through Zerodha's Kite Connect API
   - Ability to download historical candle data for specified instruments
   - Support for updating instruments to latest data

3. **Real-time Data Processing**
   - Framework for processing tick data (through `DataTick` class)
   - Interface for updatable bar series (`UpdatableBarSeriesLoader`)

## Planned Enhancements

1. **Real-time Bar Series Updates**
   - Implement the empty `updateBarSeries(DataTick tick)` method in `LatestBarSeriesProvider`
   - Add proper tick data validation and transformation
   - Ensure thread-safety for concurrent updates

   - **Detailed Implementation for `LatestBarSeriesProvider`**:
     - Modify the `updateBarSeries` method signature to accept a `DataTick tick` and `BarSeriesConfig barSeriesConfig` parameter and return `boolean`
     - Inside the method:
       1. Use the `loadBarSeries` method to retrieve the relevant `IntervalBarSeries` using the provided `BarSeriesConfig`
       2. Find the bar in the series that corresponds to the timestamp of the incoming tick based on the interval
       3. Update the bar's data:
          - Update high price if tick price is higher
          - Update low price if tick price is lower
          - Update close price with the current tick price
          - Update volume by adding tick volume
       4. Determine if the candle is complete by checking if the tick's timestamp has reached or passed the end time for the current bar
       5. Return `true` if the candle is complete (requires database update), otherwise return `false`
     - Implement thread-safety using appropriate synchronization or concurrent data structures to handle multiple tick updates
   - **Unit Tests**:
     - Create `LatestBarSeriesProviderTest` in the same package structure under `src/test`
     - Mock the `BarSeriesLoader` delegate dependency to test the caching behavior
     - Test the `updateBarSeries` method with various tick data scenarios:
       - Test updating existing candle (not complete)
       - Test updating a candle that becomes complete after update
       - Test handling of invalid tick data
     - Test concurrent updates to verify thread safety
     - Test proper interaction with the delegate loader
     - Verify correct determination of candle completion

2. **Real-time Market Data Integration Service**
   - Create a new service class `KiteTickerService` in the `runner.candle` package to manage real-time data feed from Zerodha
   - **Key Components**:
     - Inject `KiteConnectConfig` as a final field using constructor injection for API credentials and connection settings
     - Inject `BarSeriesHelper` for tick processing and `KiteTicker` for WebSocket communication
     - Implement a `subscribe` method that accepts a list of `Instrument` objects directly (avoiding string lookups)
     - **Tick Listener Implementation Strategy**:
       - Implement all required KiteTicker interfaces (OnTicks, OnConnect, OnDisconnect, OnError)
       - In `@PostConstruct` or dedicated init method: initialize KiteTicker with connection token
       - Register service as listener for all KiteTicker events
       - Maintain connection state with thread-safe AtomicBoolean
       - Implement scheduled health check for connection monitoring
       - Set up automatic reconnection with exponential backoff
       - Handle access token refresh when expired
       - Create a dedicated thread pool for high-volume tick processing
     - On each tick event (in the `onTicks` method):
       1. Convert the Kite tick to internal `DataTick` format using a mapper
       2. Delegate all business logic to `BarSeriesHelper.processTick(DataTick)` which will:
          - Look up the appropriate `BarSeriesConfig` for the instrument
          - Call the `updateBarSeries` method on `LatestBarSeriesProvider`
          - Handle candle completion logic and database batch queueing
     - Implement proper connection management (reconnection, error handling, etc.)
     - Add thread-safety mechanisms to handle high-frequency tick data
     - Keep the `onTicks` implementation clean and focused only on receiving and delegating ticks
     - **Performance Optimization**:
       - Minimize object creation during tick processing
       - Consider object pooling for DataTick conversion
       - Use efficient data structures for instrument token lookup
       - Implement non-blocking design where possible
   - **Unit Tests**:
     - Create `KiteTickerServiceTest` in the same package structure under `src/test`
     - Use Mockito to mock WebSocket connections and event handling
     - Mock `KiteConnectConfig` for testing connection establishment
     - Mock `KiteTicker` to simulate WebSocket events
     - Mock `BarSeriesHelper` to verify correct delegation of tick processing
     - Test the `subscribe` method with various instrument lists
     - Test the `onTicks` method correctly delegates to BarSeriesHelper without business logic
     - Test connection lifecycle methods:
       - Verify initialization and listener registration in @PostConstruct
       - Test proper handling of connection events (connect, disconnect, error)
       - Verify reconnection logic with backoff strategy
       - Test token refresh handling
     - Test concurrent tick handling with multiple simulated ticks
     - Test error isolation (one bad tick shouldn't affect others)
     - Test performance under high tick volumes
     - Test instrument subscription management
     - Verify clean separation between WebSocket handling and business logic

   - **BarSeriesHelper Enhancement**:
      - Expand this class to be the central business logic handler for tick processing:
      - Add a `processTick(DataTick tick)` method that will:
        1. Look up the appropriate `BarSeriesConfig` for the instrument in the tick
        2. Call `LatestBarSeriesProvider.updateBarSeries()` with the tick and config
        3. If the candle is complete (when `updateBarSeries` returns true), add it to the batch update queue using `DatabaseBatchUpdateService`
        4. Implement proper error handling and logging
      - Add method `getConfigForInstrumentToken(long instrumentToken)` to map instrument tokens to configs
      - Add caching for instrument token to config mappings to optimize high-frequency processing
      - Implement thread-safety for all shared data structures
      - **Unit Tests**:
        - Create `BarSeriesHelperTest` in the same package structure under `src/test`
        - Mock `LatestBarSeriesProvider` to test tick processing logic
        - Mock `DatabaseBatchUpdateService` to verify batch queue interactions
        - Test the complete flow from tick processing to database queueing
        - Test handling of different return values from `updateBarSeries`
        - Test instrument token to config mapping functionality
        - Verify proper error handling and exception scenarios
        - Test thread safety with concurrent tick processing

   3. **Database Batch Update Service**
   - Create a new service class `DatabaseBatchUpdateService` in the appropriate package to manage batch database updates
   - **Key Components**:
     - Include necessary database-related dependencies (repositories, entities) as final fields
     - Implement a thread-safe queue to store completed candles that need to be persisted
     - Create a scheduled runner that executes once per minute to process all items in the queue
     - Provide a public method for clients to add items to the queue (primarily used when `LatestBarSeriesProvider.updateBarSeries()` returns true)
     - Implement proper error handling and retry mechanisms for failed database operations
     - Use appropriate thread synchronization techniques to ensure queue integrity
   - **Unit Tests**:
     - Create `DatabaseBatchUpdateServiceTest` in the same package structure under `src/test`
     - Test the queue operations with mock data to verify thread safety
     - Use Mockito to mock all database-related dependencies (repositories)
     - Test the scheduled processing functionality using appropriate testing techniques for scheduled tasks
     - Verify error handling mechanisms by simulating repository failures
     - Test concurrent additions to the queue to ensure thread safety
     - Verify that completed candles are correctly processed and saved

   4. **Enhanced Caching Strategy**
   - Optimize the caching mechanism for bar series data
   - Add cache invalidation strategy for outdated data

   5. **Integration Testing**
   - Create integration tests to verify the complete flow from tick data receipt to database updates
   - Set up test configurations that simulate the real environment
   - Test the interaction between `KiteTickerService`, `LatestBarSeriesProvider`, and `DatabaseBatchUpdateService`
   - Verify that completed candles are correctly identified and processed
   - Test the batch processing with simulated database operations
   - Use test containers or in-memory databases for integration testing
   - Test concurrent processing of multiple ticks and verify thread safety

   6. **Error Handling and Robustness in KiteTickerService**
   - Implement comprehensive error handling strategy:
     - Create dedicated exception types for different error categories
     - Implement fallback mechanisms for temporary connectivity issues
     - Set up circuit breaker pattern for persistent failures
     - Design error reporting and monitoring mechanisms
     - Implement graceful degradation when services are unavailable
   - Add resilience features:
     - Implement throttling for high volume periods
     - Set up bulkhead pattern to isolate failures
     - Create retry mechanisms with appropriate backoff strategies
     - Implement timeout handling for all external calls
     - Set up dead letter queues for failed processing attempts
   - Design recovery mechanisms:
     - Implement self-healing through automated recovery procedures
     - Create state reconciliation for missed tick data
     - Set up periodic system health checks
     - Implement graceful service restart capabilities
     - Design data consistency verification procedures

   7. **Documentation**
   - Add comprehensive JavaDoc to all interfaces and implementations
   - Document the system architecture and data flow
   - Document the candle update process and database synchronization
   - Document the batch processing mechanism for database updates
   - Document testing strategies and approaches for each component
   - Document error handling and recovery procedures
   - Create runbooks for common operational scenarios

## Next Steps

The immediate focus will be on implementing the real-time bar series update functionality, the KiteTicker integration service, and the DatabaseBatchUpdateService to allow the system to process incoming market data ticks, update the cached bar series accordingly, and efficiently persist completed candles to the database in batches. Alongside implementation, comprehensive unit tests will be developed using mocking frameworks to ensure robustness, thread safety, and correct behavior of all components. Integration tests will also be created to verify the complete data flow from tick reception to database persistence. This will enable the system to maintain up-to-date candle data for technical analysis and trading decisions while optimizing database operations through batch processing.

---

*This specification will be updated incrementally as the project evolves.*
