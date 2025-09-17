SubscriptionController - curl examples

Endpoint:
POST http://localhost:8080/api/subscriptions
Content-Type: application/json
Body: { "symbol": "<SYMBOL>", "index": <true|false>, "status": "<optional-status>" }

1) Create a single-symbol subscription (non-index)
This will attempt to create a subscription for trading symbol "RELIANCE".

curl -X POST 'http://localhost:8080/api/subscriptions' \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"RELIANCE","index":false,"status":"ACTIVE"}'

Example successful response (HTTP 200):
{
  "created": ["RELIANCE"],
  "skipped": [],
  "message": "created=1 skipped=0"
}

If the symbol already exists you'll see it under "skipped".

2) Create subscriptions by expanding an index (index=true)
This will ask IndexSymbolService to expand "NIFTY50" and create subscriptions for each constituent.

curl -X POST 'http://localhost:8080/api/subscriptions' \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"NIFTY50","index":true}'

Example response:
{
  "created": ["RELIANCE","TCS","INFY", ...],
  "skipped": ["ALREADY_EXISTS_SYMBOL"],
  "message": "created=10 skipped=1"
}

3) Example with Authorization header (if your app requires auth)
Replace "Bearer <token>" with your real token:

curl -X POST 'http://localhost:8080/api/subscriptions' \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -d '{"symbol":"NIFTY50","index":true}'

Notes:
- The response contains two arrays: "created" (symbols that were inserted) and "skipped" (symbols that already existed or failed).
- New subscriptions are created with createdAt and lastUpdatedAt set to server time and latestTimestamp left null so the updater will fetch historical data according to HistoricalDateLimit on first run.
- If your API is mounted under a different base path or requires additional headers, adapt the URL and headers accordingly.
