# Chart Controller Testing Guide

This guide provides instructions for testing the Chart Controller, which generates financial charts using the MatplotChartCreator.

## Prerequisites

Before testing the Chart Controller, ensure you have:

1. The application running locally or on a server
2. Postman or a similar API testing tool installed
3. Basic knowledge of REST API testing

## Setup

1. Start the application by running:
   ```
   ./gradlew bootRun
   ```

2. Ensure the application is running and accessible (default: http://localhost:8080)

## Testing with Postman

### Test 1: Generate a Test Chart

This is the simplest test that doesn't require any real market data.

1. Open Postman
2. Create a new GET request to: `http://localhost:8080/api/charts/test`
3. Send the request
4. You should see a candlestick chart with sample data in the response

### Test 2: Generate a Basic Chart for a Real Instrument

1. Open Postman
2. Create a new GET request to: `http://localhost:8080/api/charts/generate`
3. Add query parameters:
   - `symbol`: A valid instrument symbol (e.g., "RELIANCE", "INFY")
   - `interval`: "day" (or another valid interval)
4. Send the request
5. You should see a chart for the specified instrument in the response

### Test 3: Generate a Custom Chart

1. Open Postman
2. Create a new GET request to: `http://localhost:8080/api/charts/custom`
3. Add query parameters:
   - `symbol`: A valid instrument symbol (e.g., "RELIANCE", "INFY")
   - `interval`: "day" (or another valid interval)
   - `chartType`: "LINE" (or "CANDLESTICK", "BAR")
   - `showVolume`: "true" or "false"
   - `showLegend`: "true" or "false"
   - `title`: A custom title for the chart
4. Send the request
5. You should see a customized chart in the response

## Saving Charts

To save a chart from Postman:

1. After receiving the chart image in the response
2. Click on "Save Response"
3. Select "Save as File"
4. Choose a location and filename with a `.png` extension

## Troubleshooting

### No Data Found

If you receive a 404 error with "No data found", try:
- Using a different instrument symbol
- Checking if the symbol is correctly spelled
- Using a different interval
- Verifying that market data is available for the requested instrument

### Internal Server Error

If you receive a 500 error:
- Check the application logs for detailed error information
- Verify that the MatplotChartCreator is properly configured
- Ensure Python and required libraries are installed if using the py4j implementation

## Additional Resources

For more detailed information about the Chart Controller API, refer to:
- [Chart Controller API Documentation](/docs/ChartController_API.md)
- [Matplotlib Integration Documentation](/spec/chart_matplot/matplotlib_integration.md)

## Notes

- The test endpoint (`/api/charts/test`) is particularly useful for verifying that the chart generation system is working correctly without requiring real market data
- For production use, consider implementing caching for frequently requested charts to improve performance
- The chart images are generated on-demand and are not stored on the server by default