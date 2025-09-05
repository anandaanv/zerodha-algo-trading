# Multi-Timeframe Chart Analysis API Specification

## Overview
This API enhances the existing chart generation capabilities by providing a way to generate multiple charts for different timeframes and analyze them using a custom GPT model. The API will accept a JSON payload with parameters for the symbol, timeframes, and number of candles to plot.

## API Endpoint
- **Path**: `/api/v1/charts/analyze`
- **Method**: POST
- **Content-Type**: application/json

## Request Parameters
```json
{
  "symbol": "SBIN",
  "timeframes": ["OneMinute", "FiveMinute", "OneHour", "Day"],
  "candleCount": 100
}
```

### Parameter Details
- **symbol** (required): The trading symbol for which to generate charts
- **timeframes** (required): An array of timeframe values from the Interval enum
  - Valid values: OneMinute, ThreeMinute, FiveMinute, FifteenMinute, ThirtyMinute, OneHour, FourHours, Day, Week
- **candleCount** (optional): Number of candles to include in each chart (default: 100)

## Processing Flow
1. Validate the input parameters
2. For each timeframe:
   - Fetch the IntervalBarSeries using LatestBarSeriesProvider
   - Generate a chart image using the ComplexChartCreator service
   - Save the image to a temporary directory
3. Collect all generated images
4. Call the custom GPT model using the OpenAI API
5. Return the analysis results along with links to the generated charts

## Response Format
```json
{
  "analysis": "Detailed analysis from the GPT model",
  "charts": [
    {
      "timeframe": "OneMinute",
      "url": "/charts/temp/SBIN_OneMinute_20250808153700.png"
    },
    {
      "timeframe": "FiveMinute",
      "url": "/charts/temp/SBIN_FiveMinute_20250808153700.png"
    }
  ]
}
```

## OpenAI Integration
- The API will use the existing OpenAI API key configured in application.properties
- It will call the custom GPT model: g-688a01306d1c819197681420c87e6c3e-stwr-macd-analyst
- The GPT model will be provided with the generated chart images for analysis

## Implementation Details

### Components Required
1. **Controller**: New endpoint in ChartController to handle the request
2. **Service**: New service class to orchestrate the chart generation and GPT analysis
3. **DTO**: Request and response data transfer objects

### Dependencies
- LatestBarSeriesProvider: For fetching bar series data
- ComplexChartCreator: For generating chart images
- OpenAI API Client: For calling the custom GPT model

### Error Handling
- Invalid symbol: Return 400 Bad Request with appropriate error message
- Invalid timeframe: Return 400 Bad Request with appropriate error message
- Chart generation failure: Return 500 Internal Server Error with details
- OpenAI API failure: Return 502 Bad Gateway with details

## Security Considerations
- Rate limiting should be implemented to prevent abuse
- API key validation should be considered for production use
- Temporary files should be cleaned up after processing

## Future Enhancements
- Add support for technical indicators selection
- Allow customization of chart appearance
- Implement caching of frequently requested charts
- Add support for comparison of multiple symbols