#!/bin/bash

# Test script for Chart Analysis & Snapshot API endpoints
# Usage: ./test-endpoints.sh <JWT_TOKEN>

BASE_URL="http://localhost:8080"
TOKEN="${1:-YOUR_JWT_TOKEN_HERE}"

echo "========================================"
echo "Testing Chart Analysis & Snapshot APIs"
echo "========================================"
echo ""

# Test 1: Health check
echo "1. Testing server health..."
curl -s -o /dev/null -w "Status: %{http_code}\n" "$BASE_URL/api/analysis/fundamentals?symbol=TCS" \
  -H "Authorization: Bearer $TOKEN"
echo ""

# Test 2: Stock Analysis
echo "2. Testing stock analysis..."
curl -X POST "$BASE_URL/api/analysis/analyze" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "symbol": "TCS",
    "timeframe": "1d"
  }' | jq '.' || echo "Response (raw):"
echo ""

# Test 3: Pattern Validation (Preview)
echo "3. Testing pattern validation..."
curl -X POST "$BASE_URL/api/snapshots/validate" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "symbol": "TCS",
    "timeframe": "1d",
    "chartStateJson": "{\"sources\":{\"LineToolTrendLine_1\":{\"type\":\"LineToolTrendLine\",\"points\":[{\"time\":1710000000,\"price\":3800},{\"time\":1712000000,\"price\":3900}]}}}",
    "userComment": "Trendline breakout on TCS",
    "visibility": "private",
    "performAiValidation": true
  }' | jq '.' || echo "Response (raw):"
echo ""

# Test 4: Get snapshot stats
echo "4. Testing snapshot stats..."
curl "$BASE_URL/api/snapshots/stats" \
  -H "Authorization: Bearer $TOKEN" | jq '.' || echo "Response (raw):"
echo ""

# Test 5: Get my snapshots
echo "5. Testing my snapshots list..."
curl "$BASE_URL/api/snapshots/my-snapshots?page=0&size=5" \
  -H "Authorization: Bearer $TOKEN" | jq '.content | length' || echo "Response (raw):"
echo ""

# Test 6: Get public snapshots
echo "6. Testing public snapshots..."
curl "$BASE_URL/api/snapshots/public?page=0&size=5" \
  -H "Authorization: Bearer $TOKEN" | jq '.content | length' || echo "Response (raw):"
echo ""

echo "========================================"
echo "Tests completed!"
echo "========================================"
