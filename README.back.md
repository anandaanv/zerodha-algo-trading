# AI-Powered Algorithmic Trading Platform

An intelligent algorithmic trading system built for Zerodha that combines technical analysis, AI-powered chart interpretation, and custom screening capabilities across multiple market segments.

## Overview

This platform enables traders to build sophisticated trading strategies by combining:
- Technical analysis across multiple timeframes and instruments
- Visual chart analysis using AI (OpenAI Vision)
- Cross-segment correlation (Equity, F&O, Options data)
- Custom Kotlin-based screeners
- Interactive TradingView chart generation with technical studies

## Key Features

### 1. Advanced Multi-Segment Screeners

Create powerful screeners that analyze relationships across different market segments:
- **Technical Analysis on Spot + F&O**: Perform TA on both spot prices and derivatives simultaneously
- **Options Data Integration**: Analyze Open Interest, Put-Call Ratios, and option chain data
- **Cross-Market Correlation**: Make equity decisions based on F&O behavior and vice versa
- **Example**: Buy SBIN Futures based on Open Interest analysis of nearest ITM Put options

![Screener Demo](images/screener-config.webm)

### 2. Kotlin Scripting for Custom Screeners

Build dynamic, type-safe screeners using Kotlin:
- **Scriptable Strategies**: Write screening logic in `.kts` files without recompiling
- **Full Language Power**: Access to Kotlin's expressive syntax and type system
- **Hot Reload**: Modify strategies on-the-fly without restarting the application
- **Reusable Components**: Build library of screening functions and indicators

### 3. AI-Powered Visual Chart Analysis

Generate and analyze multi-timeframe charts using OpenAI:
- **Multi-Panel Charts**: View 1m, 5m, 15m, 1h, Daily timeframes simultaneously
- **Technical Studies**: Automatically draw trendlines, support/resistance, Fibonacci retracements, Elliott Waves
- **AI Interpretation**: Send chart screenshots to OpenAI Vision for pattern recognition and trade validation
- **Interactive Drawing**: Add custom studies and annotations via the chart UI

![Trading View Demo](images/Using-trading-view.webm)

### 4. OpenAI Integration for Strategy Validation

Validate trading ideas using AI:
- **Natural Language Queries**: Ask OpenAI to analyze specific chart patterns or market conditions
- **Multi-Timeframe Context**: AI receives visual context from all timeframes
- **Kotlin + AI Hybrid**: Combine programmatic screening with AI-powered insight validation
- **Trade Confirmation**: Get AI second opinion before executing trades

![Trades Demo](images/trades.webm)

### 5. Interactive Chart Generation

Powerful charting capabilities:
- **TradingView-Style Charts**: Professional-grade candlestick charts using Lightweight Charts library
- **Custom Layouts**: 2x2, 3x1, or custom grid layouts for multiple timeframes
- **Technical Indicators**: Volume, Moving Averages, RSI, MACD, Bollinger Bands
- **Drawing Tools**: Trendlines, Fibonacci levels, Elliott Wave patterns, Support/Resistance zones
- **Export & Share**: Save charts as PNG for analysis or documentation

## Architecture

### Backend (Java/Kotlin + Spring Boot)
- **Spring Boot 3.x**: Modern Java web framework
- **Ta4j**: Technical analysis library for indicators and strategies
- **Zerodha Kite Connect**: Official API integration for trading
- **Selenium + Headless Chrome**: Chart rendering engine
- **JPA + MySQL**: Data persistence layer
- **Kotlin Scripting Engine**: Dynamic strategy execution

### Frontend (React + TypeScript)
- **React 18**: Modern UI framework
- **Vite**: Fast build tooling
- **Lightweight Charts**: TradingView-compatible charting
- **Chart Drawing Tools**: Interactive technical study creation

## Technology Stack

- **Java 21** with Kotlin support
- **Spring Boot 3.3.2**
- **Ta4j 0.18** (Technical Analysis)
- **OpenAI API** (GPT-4 Vision)
- **Zerodha Kite Connect 3.5.1**
- **Selenium WebDriver 4.15**
- **React 18** + **TypeScript**
- **MySQL 8.0**

## Getting Started

### Prerequisites

- Java 21 or higher
- MySQL 8.0+
- Node.js 18+ and npm
- Google Chrome (for headless chart rendering)
- Zerodha Kite Connect API credentials
- OpenAI API key

### Configuration

Create `application.properties`:

```properties
# Zerodha Configuration
zerodha.api.key=your_api_key
zerodha.api.secret=your_api_secret
zerodha.user.id=your_user_id

# OpenAI Configuration
openai.api.key=your_openai_key

# Database
spring.datasource.url=jdbc:mysql://localhost:3306/algotrading
spring.datasource.username=your_db_user
spring.datasource.password=your_db_password

# Chart Generation
charts.output.directory=./charts
headless.browser.path=google-chrome
```

### Build & Run

```bash
# Build backend + frontend
./gradlew build

# Run application
./gradlew bootRun

# Or use the start script
./start.sh
```

The application will start on `http://localhost:8080`

### Frontend Development

```bash
cd ui/chart-draw-app
npm install
npm run dev
```

Frontend dev server runs on `http://localhost:5173`

## API Endpoints

### Chart Generation

```bash
# Generate multi-panel chart
GET /api/charts/tradingview/multipanel?symbol=RELIANCE&layout=2x2&showVolume=true

# Custom chart with specific timeframes
POST /api/charts/tradingview
Content-Type: application/json

{
  "symbol": "INFY",
  "timeframes": ["OneMinute", "FiveMinute", "FifteenMinute", "OneHour"],
  "candleCount": 100,
  "layout": "2x2",
  "showVolume": true
}
```

### Screener Execution

```bash
# Run a Kotlin screener script
POST /api/screener/execute
Content-Type: application/json

{
  "scriptName": "my-screener.kts",
  "parameters": {
    "minVolume": 1000000,
    "rsiThreshold": 70
  }
}
```

## Custom Screener Example

Create a file in `screener/my-strategy.kts`:

```kotlin
import com.dtech.algo.screener.*
import org.ta4j.core.indicators.*

// Define screening criteria
screener {
    name = "High Volume Breakout"

    // Spot market criteria
    spot {
        indicator(RSI) { period = 14 } above 60
        indicator(Volume) greaterThan averageVolume * 2
    }

    // Futures market criteria
    futures {
        indicator(RSI) { period = 14 } above 55
        priceChange(percent = true) greaterThan 2.0
    }

    // Options data criteria
    options {
        putCallRatio lessThan 0.8
        openInterestChange greaterThan 10.0
    }

    // AI validation
    aiValidation {
        prompt = "Analyze this multi-timeframe chart for bullish breakout pattern"
        confidence = 0.75
    }
}
```

## Development Practices

- **Test-Driven Development**: Comprehensive test coverage required
- **CI/CD**: GitHub Actions for automated testing and builds
- **Code Quality**: SonarQube integration for code health
- **Package Convention**: New code goes in `com.dtech.algo` package (100% coverage target)

## Project Status

**Active Development** - The platform is continuously evolving with new features.

### Recent Additions
- ✅ Multi-timeframe chart generation
- ✅ OpenAI Vision integration for chart analysis
- ✅ Kotlin scripting engine for dynamic screeners
- ✅ React-based chart drawing UI
- ✅ Cross-segment analysis capabilities

### Roadmap
- [ ] Web/Mobile app for strategy building UI
- [ ] Real-time trade execution via Zerodha WebSockets
- [ ] Multi-broker support (beyond Zerodha)
- [ ] Backtesting engine with historical data
- [ ] Portfolio management and risk analytics
- [ ] Community strategy marketplace

## Contributing

We welcome contributions of all kinds:
- 💻 **Code**: Bug fixes, features, optimizations
- 📝 **Documentation**: Tutorials, API docs, examples
- 🧪 **Testing**: Writing tests, reporting bugs
- 💡 **Ideas**: Feature suggestions, architecture improvements
- 💰 **Sponsorship**: Help fund development

**Looking For**: React/Flutter developers to enhance the frontend experience!

## Demo Videos

- [Screener Configuration](images/screener-config.webm)
- [TradingView Chart Usage](images/Using-trading-view.webm)
- [Trade Execution Flow](images/trades.webm)

## Documentation

- [Chart Controller API](docs/ChartController_API.md)
- [Matplotlib Chart Usage](docs/matplotlib_chart_usage.md)
- [Subscription Management](docs/subscription_curl.md)

## License

This project is open source. See LICENSE file for details.

## Disclaimer

This software is for educational and research purposes. Trading in financial markets involves substantial risk. Always test strategies thoroughly before using real money. The authors assume no liability for any financial losses incurred through the use of this software.

## Support

For questions, issues, or discussions:
- GitHub Issues: Report bugs and feature requests
- Discussions: Share strategies and ideas

---

**Built with ❤️ for the algo trading community**
