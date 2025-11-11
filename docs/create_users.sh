#!/bin/bash

# Create ADMIN user: anand / protrader
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "anand",
    "password": "protrader",
    "role": "ADMIN"
  }'

echo -e "\n\n"

# Create MODERATOR user: moderator / modpass (optional)
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "moderator",
    "password": "modpass",
    "role": "MODERATOR"
  }'

echo -e "\n\n"

# Create USER: trader / trader123 (optional)
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "trader",
    "password": "trader123",
    "role": "USER"
  }'

echo -e "\n"
