# Dhan API Integration Guide

## Overview

This guide covers the complete integration of Dhan API v2 into the algo trading platform. Dhan is an alternative broker to Zerodha, and this integration follows the same architectural patterns as the existing Kite Connect integration.

## Architecture

The Dhan integration follows the **MarketDataProvider** pattern used throughout the application:

```
┌─────────────────────────────────────────────────────┐
│              Application Layer                      │
│   (Strategies, Backtesting, Screening, etc.)       │
└─────────────────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────┐
│           MarketDataProvider Interface              │
│  (loadBarSeries, getQuote, getHistoricalData)     │
└─────────────────────────────────────────────────────┘
          │                  │                  │
          ▼                  ▼                  ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│   Database   │  │   Zerodha    │  │     Dhan     │
│   Provider   │  │   Provider   │  │   Provider   │
└──────────────┘  └──────────────┘  └──────────────┘
```

## Components

### 1. Core Components

#### DhanApiClient
**Path:** `src/main/java/com/dtech/dhan/client/DhanApiClient.java`

REST client for Dhan API v2 endpoints:
- `getHistoricalDaily()` - Daily OHLC data
- `getHistoricalIntraday()` - Intraday data (1, 5, 15, 25, 60 min)
- `getQuote()` - Market quote for single instrument
- `getLTP()` - Last traded price for multiple instruments

**Base URL:** `https://api.dhan.co/v2`

#### DhanConnectConfig
**Path:** `src/main/java/com/dtech/dhan/config/DhanConnectConfig.java`

Configuration and token management:
- Loads credentials from `app_secrets` table or environment variables
- Manages access token lifecycle
- Provides configuration status checks

**Configuration Properties:**
- `dhan.enabled` - Enable/disable Dhan integration (default: false)
- `dhan.client.id` - Dhan client ID
- `dhan.access.token` - Access token (from Dhan portal)
- `dhan.user.id` - Dhan user ID
- `dhan.api.base-url` - API base URL (default: https://api.dhan.co/v2)
- `dhan.api.timeout` - Request timeout in ms (default: 30000)

#### DhanMarketDataProvider
**Path:** `src/main/java/com/dtech/dhan/service/DhanMarketDataProvider.java`

Implements `MarketDataProvider` interface:
- Converts Dhan API responses to Zerodha format for compatibility
- Supports all market data operations
- Priority: 15 (between Zerodha and Database)

#### DhanBarSeriesLoader
**Path:** `src/main/java/com/dtech/dhan/service/DhanBarSeriesLoader.java`

Implements `BarSeriesLoader` interface for strategy backtesting:
- Fetches historical data from Dhan
- Converts to `IntervalBarSeries` format
- Caches results for performance

### 2. Data Models

#### DhanQuote
**Path:** `src/main/java/com/dtech/dhan/model/DhanQuote.java`

Market quote data:
- Security ID and exchange segment
- OHLC data (open, high, low, close)
- Last traded price, quantity, time
- Bid/Ask prices and quantities
- Volume

#### DhanHistoricalData
**Path:** `src/main/java/com/dtech/dhan/model/DhanHistoricalData.java`

Historical OHLC candlestick data:
- Timestamp
- Open, High, Low, Close
- Volume
- Open Interest (for derivatives)

#### DhanTick
**Path:** `src/main/java/com/dtech/dhan/model/DhanTick.java`

WebSocket tick data (Phase 2 - not yet implemented):
- Real-time price updates
- Market depth (20 levels)
- Modes: ticker, quote, full

### 3. Database Components

#### DhanConnectSettings Entity
**Path:** `src/main/java/com/dtech/dhan/persistence/DhanConnectSettings.java`

JPA entity for storing Dhan authentication:
- Singleton pattern (id=1)
- Client ID, Access Token, User ID
- Last updated timestamp

#### DhanConnectSettingsRepository
**Path:** `src/main/java/com/dtech/dhan/repository/DhanConnectSettingsRepository.java`

Spring Data JPA repository for DhanConnectSettings.

#### Database Migration
**Path:** `docs/sql/dhan_connect_settings_migration.sql`

Creates `dhan_connect_settings` table:
```sql
CREATE TABLE IF NOT EXISTS dhan_connect_settings (
    id BIGINT PRIMARY KEY,
    client_id VARCHAR(100),
    access_token VARCHAR(500),
    user_id VARCHAR(100),
    updated_at TIMESTAMP,
    CONSTRAINT chk_singleton CHECK (id = 1)
);
```

### 4. REST API

#### DhanController
**Path:** `src/main/java/com/dtech/dhan/web/DhanController.java`

REST endpoints for Dhan configuration:

**GET /api/dhan/status** - Get configuration status
- Authentication: Any authenticated user
- Returns: configured status, masked client ID, user ID, token presence

**POST /api/dhan/update-token** - Update access token
- Authentication: ADMIN role required
- Body: `{ "accessToken": "...", "userId": "..." }`
- Updates token in database and in-memory config

**GET /api/dhan/test** - Test API connection
- Authentication: ADMIN role required
- Validates configuration status

## Setup Instructions

### Step 1: Database Setup

Run migration scripts in order:

```bash
# Create dhan_connect_settings table
mysql -u username -p database_name < docs/sql/dhan_connect_settings_migration.sql

# Insert credentials into app_secrets (optional - can also use environment variables)
mysql -u username -p database_name < docs/sql/dhan_app_secrets.sql
```

### Step 2: Get Dhan Credentials

1. Login to Dhan web portal: https://dhan.co
2. Navigate to **Settings > API Access**
3. Generate Access Token
   - Note: Dhan tokens don't expire daily like Zerodha
   - They remain valid until manually revoked
4. Copy your **Client ID** and **User ID** from account settings

### Step 3: Configure Application

#### Option A: Environment Variables (Recommended for Development)

```bash
export DHAN_ENABLED=true
export DHAN_CLIENT_ID=your_client_id
export DHAN_ACCESS_TOKEN=your_access_token
export DHAN_USER_ID=your_user_id
```

#### Option B: Database Configuration (Recommended for Production)

Update `docs/sql/dhan_app_secrets.sql` with your credentials and run:

```sql
INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('dev', 'dhan.client.id', 'YOUR_ACTUAL_CLIENT_ID')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_ACTUAL_CLIENT_ID';

INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('dev', 'dhan.access.token', 'YOUR_ACTUAL_ACCESS_TOKEN')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_ACTUAL_ACCESS_TOKEN';

INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('dev', 'dhan.user.id', 'YOUR_ACTUAL_USER_ID')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_ACTUAL_USER_ID';
```

#### Option C: Application Properties (Not Recommended - Security Risk)

Edit `src/main/resources/application.properties`:

```properties
dhan.enabled=true
dhan.client.id=your_client_id
dhan.access.token=your_access_token
dhan.user.id=your_user_id
```

### Step 4: Enable Dhan Provider

Set market data provider to Dhan:

```bash
export MARKET_DATA_PROVIDER=dhan
# OR
export DHAN_ENABLED=true
```

### Step 5: Start Application

```bash
./gradlew bootRun
```

The application will:
1. Load Dhan configuration from database or environment
2. Initialize DhanConnectConfig
3. Register DhanMarketDataProvider and DhanBarSeriesLoader
4. Make Dhan endpoints available

## Usage

### Updating Access Token via API

```bash
# Login as admin
TOKEN=$(curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"your_password"}' \
  | jq -r '.token')

# Update Dhan token
curl -X POST http://localhost:8080/api/dhan/update-token \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "accessToken": "your_new_access_token",
    "userId": "your_user_id"
  }'
```

### Checking Status

```bash
# Check Dhan configuration status
curl http://localhost:8080/api/dhan/status \
  -H "Authorization: Bearer $TOKEN"
```

Response:
```json
{
  "configured": true,
  "clientId": "1234****5678",
  "userId": "DH12345",
  "hasAccessToken": true
}
```

### Testing Connection

```bash
curl http://localhost:8080/api/dhan/test \
  -H "Authorization: Bearer $TOKEN"
```

## Market Data Provider Selection

The application supports multiple market data providers with automatic fallback:

```properties
# Set primary provider
market.data.provider=dhan

# Available options:
# - database: Local database cache (fastest, requires pre-populated data)
# - zerodha: Zerodha Kite API (requires Kite authentication)
# - dhan: Dhan API (requires Dhan authentication)
```

**Provider Priority (if multiple enabled):**
1. Database Provider (Priority: 20)
2. Dhan Provider (Priority: 15)
3. Zerodha Provider (Priority: 10)

The system automatically selects the highest priority available provider.

## Exchange and Instrument Mapping

### Dhan Exchange Segments

| Zerodha Exchange | Dhan Exchange Segment |
|-----------------|----------------------|
| NSE             | NSE_EQ              |
| BSE             | BSE_EQ              |
| NFO             | NSE_FNO             |
| BFO             | BSE_FNO             |
| MCX             | MCX_COMM            |

### Dhan Instrument Types

| Zerodha Type | Dhan Instrument Type |
|-------------|---------------------|
| EQ          | EQUITY              |
| FUT         | FUTIDX              |
| CE/PE       | OPTIDX              |

### Interval Mapping

| Interval | Dhan API Value |
|----------|---------------|
| 1 minute | 1             |
| 5 minute | 5             |
| 15 minute| 15            |
| 25 minute| 25            |
| 60 minute| 60            |
| day      | "daily"       |

## API Endpoints Reference

### Dhan API v2 Endpoints Used

#### Historical Data - Daily
**POST** `https://api.dhan.co/v2/charts/historical`

Request:
```json
{
  "securityId": "1333",
  "exchangeSegment": "NSE_EQ",
  "instrument": "EQUITY",
  "expiryCode": 0,
  "fromDate": "2024-01-01",
  "toDate": "2024-01-31"
}
```

#### Historical Data - Intraday
**POST** `https://api.dhan.co/v2/charts/intraday`

Request:
```json
{
  "securityId": "1333",
  "exchangeSegment": "NSE_EQ",
  "instrument": "EQUITY",
  "interval": 5,
  "fromDate": "2024-01-01",
  "toDate": "2024-01-31"
}
```

#### Market Quote
**POST** `https://api.dhan.co/v2/marketfeed/quote`

Request:
```json
{
  "instruments": [
    {
      "securityId": "1333",
      "exchangeSegment": "NSE_EQ"
    }
  ]
}
```

#### Last Traded Price (Batch)
**POST** `https://api.dhan.co/v2/marketfeed/ltp`

Request:
```json
{
  "instruments": [
    {
      "securityId": "1333",
      "exchangeSegment": "NSE_EQ"
    },
    {
      "securityId": "11536",
      "exchangeSegment": "NSE_EQ"
    }
  ]
}
```

### Authentication

All Dhan API requests require the `access-token` header:

```
access-token: your_dhan_access_token
```

## Limitations and TODOs

### Current Limitations

1. **Instrument Mapping**: Zerodha instrument tokens are not directly compatible with Dhan security IDs
   - TODO: Create mapping table between Zerodha instruments and Dhan securities
   - Currently uses instrument token as security ID (will fail for most cases)

2. **WebSocket Real-time Data**: Not yet implemented
   - Dhan supports WebSocket for real-time ticks
   - Up to 5 concurrent connections
   - 5000 instruments per connection
   - TODO: Implement `DhanWebSocketClient` and `DhanTickerService`

3. **Quote Data Batch Operations**: Currently fetches quotes individually
   - TODO: Optimize using `getLTP()` for batch operations

4. **Derivative Instruments**: Basic support only
   - TODO: Add proper expiry code handling for F&O

### Future Enhancements (Phase 2)

1. **Instrument Mapping Service**
   - Database table to map Zerodha tokens to Dhan security IDs
   - Auto-sync from both broker instrument dumps
   - Symbol-based search and resolution

2. **WebSocket Integration**
   - Real-time tick data for live trading
   - Market depth updates
   - Order updates and position tracking

3. **Order Management**
   - Place, modify, cancel orders via Dhan API
   - Portfolio and position tracking
   - P&L calculations

4. **Multi-Broker Support**
   - Switch between brokers dynamically
   - Unified instrument resolution
   - Broker selection per strategy

## Troubleshooting

### Issue: "Dhan API not configured"

**Solution:** Check configuration:
```bash
# Check if token exists in database
mysql> SELECT * FROM dhan_connect_settings;

# Check app_secrets
mysql> SELECT * FROM app_secrets WHERE prop_key LIKE 'dhan.%';

# Check environment variables
echo $DHAN_ENABLED
echo $DHAN_ACCESS_TOKEN
```

### Issue: "Invalid security ID"

**Cause:** Instrument mapping not implemented yet.

**Temporary Solution:** Manually find Dhan security ID:
1. Search instrument on Dhan web platform
2. Use browser dev tools to capture security ID
3. Use security ID directly in API calls

**Permanent Solution:** Wait for instrument mapping service implementation.

### Issue: "Access token invalid"

**Solution:** Generate new access token from Dhan portal and update:
```bash
curl -X POST http://localhost:8080/api/dhan/update-token \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"accessToken":"new_token","userId":"your_user_id"}'
```

### Issue: DhanConnectConfig not loading

**Cause:** `dhan.enabled=false` or missing.

**Solution:**
```bash
# Enable Dhan in application.properties or environment
export DHAN_ENABLED=true
```

## Security Considerations

1. **Never commit tokens to Git**
   - Use `.gitignore` for any files with credentials
   - Store tokens in database or environment variables only

2. **ADMIN role required for token updates**
   - Only admins can update Dhan access tokens
   - Status check available to all authenticated users

3. **Token storage**
   - Access tokens stored encrypted in database (TODO: encryption)
   - Retrieved in-memory on application startup
   - Not exposed in API responses (masked)

4. **CORS Configuration**
   - Currently set to allow all origins (`*`)
   - TODO: Restrict to specific frontend origins in production

## Testing

### Unit Tests (TODO)
- DhanApiClient request/response parsing
- DhanMarketDataProvider conversion logic
- DhanBarSeriesLoader data transformation

### Integration Tests (TODO)
- End-to-end data fetching from Dhan API
- Token refresh flow
- Provider selection and fallback

### Manual Testing

Use the provided test script:
```bash
bash docs/test-dhan-endpoints.sh
```

## Support and Documentation

- **Dhan API Documentation**: https://dhanhq.co/docs/v2/
- **Project Issues**: https://github.com/yourusername/zerodha-algo-trading/issues
- **Dhan Support**: support@dhan.co

## Contributors

- Initial implementation: [Your Name]
- Date: 2024-01-XX

## License

Same as parent project.
