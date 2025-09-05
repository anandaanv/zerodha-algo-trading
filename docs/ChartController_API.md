# Chart Controller API Documentation

This document provides information about the Chart Controller API, which allows you to generate and view financial charts using the MatplotChartCreator.

## Overview

The Chart Controller provides REST endpoints for generating financial charts for different instruments and timeframes. It supports both basic and customized chart generation, as well as a test endpoint that generates sample data.

## Base URL

All API endpoints are relative to: `/api/charts`

## Endpoints

### 1. Generate Basic Chart

Generates a chart for a given instrument and interval using default settings.

**URL:** `/api/charts/generate`

**Method:** `GET`

**Query Parameters:**

| Parameter | Type   | Required | Default | Description                                      |
|-----------|--------|----------|---------|--------------------------------------------------|
| symbol    | String | Yes      | -       | The instrument symbol (e.g., "RELIANCE", "INFY") |
| interval  | String | No       | "day"   | The time interval (e.g., "day", "hour", "minute")|

**Response:**

- **Content-Type:** `image/png`
- **Body:** Binary image data (PNG format)

**Example Request:**

```
GET /api/charts/generate?symbol=RELIANCE&interval=day
```

**Status Codes:**

- `200 OK`: Chart generated successfully
- `404 Not Found`: No data found for the specified symbol and interval
- `500 Internal Server Error`: Error generating the chart

### 2. Generate Custom Chart

Generates a customized chart with specific configuration options.

**URL:** `/api/charts/custom`

**Method:** `GET`

**Query Parameters:**

| Parameter  | Type    | Required | Default       | Description                                      |
|------------|---------|----------|---------------|--------------------------------------------------|
| symbol     | String  | Yes      | -             | The instrument symbol (e.g., "RELIANCE", "INFY") |
| interval   | String  | No       | "day"         | The time interval (e.g., "day", "hour", "minute")|
| chartType  | String  | No       | "CANDLESTICK" | The chart type (e.g., "CANDLESTICK", "LINE", "BAR") |
| showVolume | Boolean | No       | true          | Whether to show volume                           |
| showLegend | Boolean | No       | true          | Whether to show legend                           |
| title      | String  | No       | -             | Custom chart title                               |

**Response:**

- **Content-Type:** `image/png`
- **Body:** Binary image data (PNG format)

**Example Request:**

```
GET /api/charts/custom?symbol=INFY&interval=day&chartType=LINE&showVolume=false&title=Infosys%20Daily%20Chart
```

**Status Codes:**

- `200 OK`: Chart generated successfully
- `404 Not Found`: No data found for the specified symbol and interval
- `500 Internal Server Error`: Error generating the chart

### 3. Generate Test Chart

Generates a test chart with sample data. This endpoint is useful for testing the chart generation without requiring real market data.

**URL:** `/api/charts/test`

**Method:** `GET`

**Response:**

- **Content-Type:** `image/png`
- **Body:** Binary image data (PNG format)

**Example Request:**

```
GET /api/charts/test
```

**Status Codes:**

- `200 OK`: Chart generated successfully
- `500 Internal Server Error`: Error generating the chart

## Using with Postman

To view charts in Postman:

1. Enter the API URL (e.g., `http://localhost:8080/api/charts/test`)
2. Send the request
3. In the response section, Postman will automatically display the image

For saving the image:

1. Click on "Save Response" in Postman
2. Select "Save as File"
3. Choose a location and filename with a `.png` extension

## Notes

- The chart images are generated on-demand and are not stored on the server
- The quality and size of the charts can be controlled through the MatplotChartCreator configuration
- For large datasets, chart generation may take longer
- The API returns binary image data directly, which can be displayed in a browser or saved as a file