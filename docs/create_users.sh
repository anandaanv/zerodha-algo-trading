#!/bin/bash

# Usage: ./script.sh [API_HOST]
# Example: ./script.sh https://tradeapi.dheemantech.in
# Default: http://localhost:8080

API_HOST="${1:-http://localhost:8080}"

echo "Using API host: $API_HOST"
echo ""

# Create ADMIN user: anand / protrader
curl -X POST "$API_HOST/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "anand",
    "password": "protrader",
    "role": "ADMIN"
  }'

echo -e "\n\n"

# Create MODERATOR user: moderator / modpass (optional)
curl -X POST "$API_HOST/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "moderator",
    "password": "modpass",
    "role": "MODERATOR"
  }'

echo -e "\n\n"

# Create USER: trader / trader123 (optional)
curl -X POST "$API_HOST/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "trader",
    "password": "trader123",
    "role": "USER"
  }'

echo -e "\n"