# AI-Based Stock Screener Specification v0.0.3 (Phase 1)

## Overview

This document outlines the basic specifications for implementing an AI-based stock screening system integrated with the existing algorithmic trading platform. The system will leverage large language models (LLMs) like GPT to analyze stocks and provide trading insights.

## Business Objectives

1. **Enhanced Analysis**: Utilize AI to analyze stock data beyond traditional rule-based methods
2. **Natural Language Interface**: Allow traders to interact with the system using natural language
3. **Scalable Processing**: Enable analysis of multiple stocks efficiently

## Phase 1 Scope (Minimal Viable Product)

### Core Components

1. **AIScreenerController**
   - REST controller with endpoints for stock analysis
   - Accepts stock symbol as parameter
   - Delegates processing to AIScreenerService

2. **AIScreenerService**
   - Core service with `scanStock` method
   - Retrieves bar series data for the requested stock
   - Formats data for LLM processing
   - Calls OpenAI API with appropriate prompts
   - Processes and returns AI analysis results

### API Design

#### Endpoints

- `GET /api/v1/ai/screen/{symbol}`: Analyze a specific stock with GPT

#### Request Parameters

- `symbol`: Path variable containing the stock symbol to analyze (e.g., "RELIANCE", "SBIN")
- `interval` (optional query parameter): Timeframe for analysis, defaults to daily

#### Response Format

```json
{
  "symbol": "RELIANCE",
  "timestamp": "2025-08-04T10:15:30Z",
  "interval": "day",
  "analysis": {
    "summary": "Reliance Industries shows strong bullish momentum with increasing volume...",
    "trend": "BULLISH",
    "confidence": 85,
    "keyLevels": {
      "support": [2580.50, 2520.25],
      "resistance": [2650.75, 2700.00]
    },
    "signals": [
      "Price above major moving averages",
      "Volume increasing on up days",
      "Recent consolidation near highs"
    ],
    "recommendation": "Consider long position with stop loss at 2520"
  },
  "metadata": {
    "processTime": 1.8,
    "modelUsed": "gpt-4-turbo"
  }
}
```

## Implementation Details

### AIScreenerService Implementation

1. **scanStock Method**
   - Core method that performs the stock analysis
   - Takes a stock symbol as parameter
   - Retrieves recent price data using existing BarSeriesProvider
   - Parameters:
     - `symbol`: Stock symbol (e.g., "RELIANCE")
     - `interval`: Timeframe for analysis (optional, defaults to daily)
   - Returns analysis results in a structured format

2. **Data Processing Flow**
   - Retrieve bar series data for the specified stock and interval
   - Calculate basic technical indicators (e.g., RSI, MACD, moving averages)
   - Format data into LLM-friendly format
   - Generate appropriate prompt with market context
   - Call OpenAI API with the prepared prompt
   - Parse and structure the LLM response
   - Return the final analysis

### Integration with Existing Systems

- Uses BarSeriesProvider to access price data
- Leverages existing BarSeriesConfig for interval configuration
- Connects to OpenAI API using environment variables for authentication

### Testing Strategy

- Unit tests for AIScreenerService with mocked OpenAI responses
- Integration tests with actual API calls using test stocks
- Performance testing for response time optimization
- Manual validation of analysis quality by trading experts

## Future Enhancements (Phase 2)

1. **Batch Analysis**
   - Add endpoint to analyze multiple stocks in one request
   - Implement parallel processing for efficiency

2. **Advanced Filtering**
   - Add capability to screen entire market based on criteria
   - Create predefined screening templates

3. **Performance Improvements**
   - Implement caching for frequently analyzed stocks
   - Optimize prompt engineering for faster, more accurate responses

4. **User Customization**
   - Allow users to customize analysis parameters
   - Support user-defined screening criteria

5. **Results Storage and Tracking**
   - Store historical analyses for comparison
   - Track performance of AI recommendations over time

6. **UI Integration**
   - Create interactive dashboard for AI screening results
   - Implement visualization of analysis results

---

*This specification will be expanded based on feedback from initial implementation.*
