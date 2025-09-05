# zerodha-algo-trading
A codebase to connect zerodha connect along with algo trading library to use power of algo trading with zerodha

**IMP: Any new code additions should go to package `com.dtech.algo`. This package is supposed to have 100% code coverage production ready.**

**I am looking for React/ Flutter developers to build a frontend app for this project**

We started with simple MVP for fetching data from Zerodha and putting trades. When it was successful, we went ahead with 
building a fully working algo trading app.

### What do we want to build?

We want to build a solution where users can build a strategies that can 
together work on different segments and make a comparison analysis in Equity, Derivatives before 
Putting trade. E.g. I want to buy SBIN Fut, but the best criteria to make that call is 
analysing open interest in the nearest In the money Put. We want to build that level of mechanism, which no one provides as of now.
# TradingView Chart Generation

This module provides functionality for generating and storing multi-panel TradingView charts using Ta4j BarSeries data.

## Features

- Render multi-panel TradingView charts using Lightweight Charts library
- Support for multiple timeframes in a grid layout
- Customizable chart appearance with options for volume indicators, legends, etc.
- Screenshot capture and storage of rendered charts
- Concurrent chart generation with rate limiting to prevent resource exhaustion

## API Endpoints

### Generate TradingView Multi-Panel Chart

```
GET /api/charts/tradingview/multipanel
```

Parameters:
- `symbol`: Trading symbol (e.g., "RELIANCE", "INFY")
- `layout`: Optional grid layout (default: "2x2")
- `showVolume`: Whether to show volume indicators (default: true)

Returns: PNG image of the multi-panel chart

### Generate Custom TradingView Charts

```
POST /api/charts/tradingview
```

Request body (JSON):
```json
{
  "symbol": "RELIANCE",
  "timeframes": ["OneMinute", "FifteenMinute", "OneHour", "Day"],
  "candleCount": 100,
  "layout": "2x2",
  "title": "Multi-Timeframe Analysis",
  "showVolume": true
}
```

Returns: JSON response with chart information and URL to the generated image

## Configuration

The following properties can be configured in `application.properties` or `application-chart.properties`:

```properties
# Chart generation configuration
charts.output.directory=./charts
charts.temp.directory=./charts/temp

# Headless browser configuration
headless.browser.path=google-chrome
headless.browser.timeout=30000

# Chart appearance settings
chart.default.width=1280
chart.default.height=800
```

## Requirements

- Java 21 or higher
- Spring Boot 3.x
- Google Chrome or Chromium (for headless rendering)
- Ta4j library for technical analysis

## Implementation Details

The chart generation process works as follows:

1. Bar series data is loaded for each requested timeframe
2. Data is converted to JSON format compatible with Lightweight Charts
3. An HTML template is populated with the chart data and configuration
4. A headless browser renders the HTML and captures a screenshot
5. The screenshot is saved to disk and returned to the client

Concurrent requests are managed with semaphores to prevent resource exhaustion, with a configurable limit per symbol.
### What do we have as of now?

After first MVP, now we have built a server side architecture to make fully configurable strategies using 
  different bar-series for technical analysis and different bar-series for trades. So now you can make a trade in 
  SBIN Fut Or SBI Cash by analysing Open interest in SBIN call and puts, or PE ratio,

### What is pending?

We have a huge backlog, because we want to build a market ready product. Some of them are 
1. Build a usable web/mobile app that can be used by our users to build strategy
2. Integrate with zerodha websockets for now for putting trades in realtime.
3. Take the library to next level with integrating with different brokers.
These are few things from the top of my mind, and the list is ever-growing!!

### Do we follow any development practices?
Yes, we follow TDD, or at least we expect reasonable coverage on the code that we write 
We have CI setup with github actions, and the code health is tracked on it.
   
> We welcome all sort of contributions, including your time and money!!

