# Phase 2: Dhan Integration - Proper Java Client with DTOs

## Completed: Dhan API Java Client

### 1. DTO Classes (Type-Safe, NOT plain JSON)

**Request DTOs:**
- `DhanHistoricalRequest.java` - For historical & intraday data requests
- `DhanQuoteRequest.java` - For market quote requests (with nested InstrumentIdentifier)
- `DhanLTPRequest.java` - For LTP (Last Traded Price) requests

**Response DTOs:**
- `DhanHistoricalResponse.java` - Historical data with nested HistoricalCandle class
- `DhanQuoteResponse.java` - Market quotes with nested QuoteData class (25+ fields)
- `DhanLTPResponse.java` - LTP data with nested LTPData class

**Key Features:**
✅ Proper Jackson annotations (@JsonProperty)
✅ Lombok builders for clean object creation
✅ Nested classes for complex structures
✅ Type-safe (Double, Long, String - not Object/Map)

### 2. DhanApiClient - RestTemplate-based HTTP Client

**File:** `src/main/java/com/dtech/dhan/client/DhanApiClient.java`

**Methods Implemented:**
```java
// Historical Data
DhanHistoricalResponse getHistoricalDaily(...)
DhanHistoricalResponse getHistoricalIntraday(...)

// Market Quotes
QuoteData getQuote(String securityId, String exchangeSegment, String accessToken)
List<QuoteData> getQuotes(List<InstrumentIdentifier> instruments, String accessToken)

// LTP
List<LTPData> getLTP(List<InstrumentIdentifier> instruments, String accessToken)
```

**Features:**
✅ Uses DTOs (NOT Map<String, Object>)
✅ Generic executePost() method with proper error handling
✅ Authentication via "access-token" header
✅ Configurable base URL and timeout
✅ Comprehensive logging

## Remaining Tasks

### 3. DhanConnectConfig (TODO)
- Load credentials from app_secrets table
- Manage access token lifecycle
- Initialize on startup

### 4. DhanConnectSettings Entity (TODO)
- JPA entity for token persistence
- Singleton pattern (id=1)

### 5. DhanMarketFacade (TODO)
- Implement MarketFacade interface
- Convert Dhan DTOs → Zerodha models for compatibility
- Handle all MarketFacade methods

### 6. Register in MarketFacadeProvider (TODO)
```java
if ("dhan".equalsIgnoreCase(brokerName)) {
    return createDhanFacade();
}
```

### 7. Database & Configuration (TODO)
- Migration script for dhan_connect_settings table
- app_secrets SQL for credentials
- application.properties for Dhan config
- DhanController for token management API
- SecurityConfig updates

## Benefits of This Approach

### ✅ Type Safety
**Before (BAD):**
```java
Map<String, Object> request = new HashMap<>();
request.put("securityId", "1333");
request.put("exchangeSegment", "NSE_EQ");
```

**After (GOOD):**
```java
DhanQuoteRequest request = DhanQuoteRequest.builder()
    .instruments(List.of(
        InstrumentIdentifier.builder()
            .securityId("1333")
            .exchangeSegment("NSE_EQ")
            .build()
    ))
    .build();
```

### ✅ Compile-Time Checking
- Typos caught at compile time
- IDE autocomplete works perfectly
- Refactoring is safe

### ✅ Clear API Contracts
- Request/Response structures documented in code
- Easy to understand what data is required/returned
- Self-documenting code

### ✅ Jackson Integration
- Automatic JSON serialization/deserialization
- No manual parsing with JsonNode
- Proper null handling

## API Structure

### Dhan API v2 Base URL
`https://api.dhan.co/v2/`

### Endpoints Implemented

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/charts/historical` | POST | Daily OHLC data |
| `/charts/intraday` | POST | Intraday data (1,5,15,25,60 min) |
| `/marketfeed/quote` | POST | Market quotes (full data) |
| `/marketfeed/ltp` | POST | Last Traded Price (lightweight) |

### Authentication
All requests require:
```
Header: access-token: YOUR_ACCESS_TOKEN
Content-Type: application/json
```

## Next Steps

1. Implement DhanConnectConfig & Settings
2. Create DhanMarketFacade
3. Register in MarketFacadeProvider
4. Test with real Dhan API credentials
5. Create git commit for Phase 2
