# TradingView Chart Integration

This directory contains the TradingView Advanced Charts integration for the application.

## Structure

```
tradingview/
├── TVChartApp.tsx           # Main component - handles single and multi-panel routing
├── TVChartContainer.tsx     # Individual chart widget wrapper
├── TVMultiPanelChart.tsx    # Multi-timeframe grid layout
├── datafeed.ts              # TradingView datafeed implementation
├── tvApi.ts                 # API integration (reuses existing backend)
└── README.md                # This file
```

## Routes

- `/chart` - TradingView charts (replaces legacy lightweight-charts)
- `/chart-legacy` - Legacy lightweight-charts implementation

## URL Parameters

### Single Chart
```
/chart?symbol=TCS&timeframe=1h
/chart?script=INFY&period=1d
```

### Multi-Panel Chart (comma-separated timeframes)
```
/chart?symbol=TCS&timeframe=15m,1h,1d
/chart?symbol=RELIANCE&timeframe=5m,15m,30m,1h
```

## Features

- ✅ Single chart view with full TradingView functionality
- ✅ Multi-panel grid layout for multiple timeframes
- ✅ Connects to existing backend API (`/api/ohlc`, `/api/symbols`, etc.)
- ✅ Symbol search integration
- ✅ Timeframe support: 1m, 3m, 5m, 15m, 30m, 1h, 2h, 1d, 1w, 1M
- ✅ Indian timezone (Asia/Kolkata)
- ✅ Market hours: 09:15 - 15:30

## Backend API Integration

The datafeed connects to these existing endpoints:
- `GET /api/symbols?query=...` - Symbol search
- `GET /api/intervals/mapping` - Timeframe mapping
- `GET /api/ohlc?symbol=...&interval=...` - Historical OHLC data

## Configuration

Chart configuration is in `TVChartApp.tsx` and `TVChartContainer.tsx`:
- Library path: `/charting_library/charting_library_cloned_data/charting_library/`
- Theme: light
- Timezone: Asia/Kolkata
- Session: 09:15-15:30

## Development

The TradingView library is loaded from the `public/charting_library/` directory, which is copied from the git submodule at `charting_library/charting_library_cloned_data/`.

To update the library:
```bash
cd charting_library
git pull
cd ..
cp -r charting_library/charting_library_cloned_data public/charting_library
```

## TODO

- [ ] Real-time data streaming via WebSocket
- [ ] Chart state persistence (save/load layouts)
- [ ] Custom studies/indicators
- [ ] Drawing tools sync across panels
- [ ] Mobile responsive optimizations
