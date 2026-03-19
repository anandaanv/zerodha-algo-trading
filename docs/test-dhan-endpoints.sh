#!/bin/bash

# Test script for Dhan API endpoints
# Usage: ./test-dhan-endpoints.sh [admin_username] [admin_password]

set -e

# Configuration
BASE_URL="${API_BASE_URL:-http://localhost:8080}"
ADMIN_USER="${1:-admin}"
ADMIN_PASS="${2:-password}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "========================================="
echo "  Dhan API Integration Test Suite"
echo "========================================="
echo ""
echo "Base URL: $BASE_URL"
echo "Admin User: $ADMIN_USER"
echo ""

# Step 1: Login as admin
echo -e "${YELLOW}Step 1: Logging in as admin...${NC}"
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token')

if [ "$TOKEN" == "null" ] || [ -z "$TOKEN" ]; then
  echo -e "${RED}✗ Login failed${NC}"
  echo "Response: $LOGIN_RESPONSE"
  exit 1
fi

echo -e "${GREEN}✓ Login successful${NC}"
echo "Token: ${TOKEN:0:20}..."
echo ""

# Step 2: Check Dhan status (before configuration)
echo -e "${YELLOW}Step 2: Checking Dhan status (before configuration)...${NC}"
STATUS_RESPONSE=$(curl -s -X GET "$BASE_URL/api/dhan/status" \
  -H "Authorization: Bearer $TOKEN")

echo "Response: $STATUS_RESPONSE"
echo ""

CONFIGURED=$(echo "$STATUS_RESPONSE" | jq -r '.configured')
if [ "$CONFIGURED" == "true" ]; then
  echo -e "${GREEN}✓ Dhan is already configured${NC}"
else
  echo -e "${YELLOW}⚠ Dhan is not configured yet${NC}"
fi
echo ""

# Step 3: Update access token (interactive)
echo -e "${YELLOW}Step 3: Update Dhan access token...${NC}"
echo ""
echo "To update Dhan credentials, you need:"
echo "  1. Dhan Access Token (from https://dhan.co > Settings > API Access)"
echo "  2. Dhan User ID"
echo ""

read -p "Do you want to update Dhan credentials now? (y/N) " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
  read -p "Enter Dhan Access Token: " DHAN_TOKEN
  read -p "Enter Dhan User ID: " DHAN_USER_ID

  if [ -z "$DHAN_TOKEN" ] || [ -z "$DHAN_USER_ID" ]; then
    echo -e "${RED}✗ Token and User ID are required${NC}"
    exit 1
  fi

  UPDATE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/dhan/update-token" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"accessToken\":\"$DHAN_TOKEN\",\"userId\":\"$DHAN_USER_ID\"}")

  echo "Response: $UPDATE_RESPONSE"
  echo ""

  if echo "$UPDATE_RESPONSE" | jq -e '.message' > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Access token updated successfully${NC}"
  else
    echo -e "${RED}✗ Failed to update access token${NC}"
    exit 1
  fi
else
  echo -e "${YELLOW}⚠ Skipping token update${NC}"
fi
echo ""

# Step 4: Check status again (after configuration)
echo -e "${YELLOW}Step 4: Checking Dhan status (after configuration)...${NC}"
STATUS_RESPONSE=$(curl -s -X GET "$BASE_URL/api/dhan/status" \
  -H "Authorization: Bearer $TOKEN")

echo "Response: $STATUS_RESPONSE"
echo ""

CONFIGURED=$(echo "$STATUS_RESPONSE" | jq -r '.configured')
HAS_TOKEN=$(echo "$STATUS_RESPONSE" | jq -r '.hasAccessToken')

if [ "$CONFIGURED" == "true" ] && [ "$HAS_TOKEN" == "true" ]; then
  echo -e "${GREEN}✓ Dhan is fully configured${NC}"
else
  echo -e "${YELLOW}⚠ Dhan configuration incomplete${NC}"
  echo "  Configured: $CONFIGURED"
  echo "  Has Token: $HAS_TOKEN"
fi
echo ""

# Step 5: Test connection
echo -e "${YELLOW}Step 5: Testing Dhan API connection...${NC}"
TEST_RESPONSE=$(curl -s -X GET "$BASE_URL/api/dhan/test" \
  -H "Authorization: Bearer $TOKEN")

echo "Response: $TEST_RESPONSE"
echo ""

if echo "$TEST_RESPONSE" | jq -e '.message' > /dev/null 2>&1; then
  echo -e "${GREEN}✓ Dhan API test successful${NC}"
else
  echo -e "${RED}✗ Dhan API test failed${NC}"
  if echo "$TEST_RESPONSE" | jq -e '.error' > /dev/null 2>&1; then
    ERROR=$(echo "$TEST_RESPONSE" | jq -r '.error')
    echo "Error: $ERROR"
  fi
fi
echo ""

# Step 6: Verify database state
echo -e "${YELLOW}Step 6: Database verification...${NC}"
echo ""
echo "Check the database tables for Dhan configuration:"
echo ""
echo "  SELECT * FROM dhan_connect_settings;"
echo "  SELECT * FROM app_secrets WHERE prop_key LIKE 'dhan.%';"
echo ""
echo -e "${YELLOW}Run these queries manually to verify database state.${NC}"
echo ""

# Summary
echo "========================================="
echo "  Test Summary"
echo "========================================="
echo ""
echo "All endpoint tests completed."
echo ""
echo "Next steps:"
echo "  1. Verify database has Dhan credentials"
echo "  2. Enable Dhan provider: export DHAN_ENABLED=true"
echo "  3. Restart application"
echo "  4. Test market data fetching"
echo ""
echo "For troubleshooting, see: docs/DHAN_INTEGRATION_GUIDE.md"
echo ""
