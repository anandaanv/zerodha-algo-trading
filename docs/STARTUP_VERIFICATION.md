# Server Startup Verification Checklist

## ✅ Things to Check After Server Starts

### 1. Check Logs for Tool Registration

Look for these messages in your server logs:

```
✅ Expected Log Messages:
- "Registered tool: trendline_breakout_validator for pattern: TRENDLINE_BREAKOUT"
- "Registered tool: triangle_pattern_validator for pattern: SYMMETRICAL_TRIANGLE"
- "Registered 2 AI validation tools"
```

If you see these, the AI Tool framework is working! 🎉

### 2. Check for Component Initialization

```
✅ Expected:
- AIToolRegistry initialized
- AIValidationOrchestrator initialized
- DrawingExtractorService initialized
- ChartSnapshotService initialized
- StockAnalysisService initialized
```

### 3. Check for Errors

```
❌ Common startup errors:
- "No bean of type AITool" → Tools not being picked up by Spring
- "OpenAI key not configured" → Missing OPENAI_API_KEY env var
- "Table doesn't exist" → Need to run migration SQL
```

## 🧪 Quick Manual Tests

### Test 1: Check if endpoints exist

Open browser or use curl:

```bash
# Should return 401 (Unauthorized) - means endpoint exists!
curl http://localhost:8080/api/snapshots/stats

# Should return 401 or empty array
curl http://localhost:8080/api/snapshots/public
```

If you get **404**, the endpoints aren't registered.
If you get **401** or actual data, endpoints are working!

### Test 2: Test with Authentication

If you have a JWT token from logging in:

```bash
# Get your token from the UI after login, then:
export TOKEN="your-jwt-token-here"

# Test analysis endpoint
curl -X POST http://localhost:8080/api/analysis/analyze \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"symbol":"TCS","timeframe":"1d"}'
```

Expected response:
```json
{
  "symbol": "TCS",
  "timeframe": "1d",
  "fundamentals": { ... },
  "news": [ ... ],
  "correlation": { ... },
  "socialSentiment": { ... }
}
```

### Test 3: Test Pattern Validation

```bash
curl -X POST http://localhost:8080/api/snapshots/validate \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "symbol": "TCS",
    "timeframe": "1d",
    "chartStateJson": "{}",
    "userComment": "Testing trendline breakout",
    "visibility": "private"
  }'
```

Expected response:
```json
{
  "isValid": false,
  "confidence": 0.0,
  "reason": "No drawings found on chart",
  "detailedFeedback": "Please add pattern drawings to the chart before validation."
}
```

This is correct! It's telling you no drawings were found.

## 🔍 Debugging Tips

### If Tools Not Registered

1. Check if `@Component` annotation is present on tool classes
2. Check if package scanning includes `com.dtech.kitecon.service.ai.tools`
3. Check Application main class has `@ComponentScan`

### If Endpoints Not Working

1. Verify `@RestController` on controller classes
2. Check `@RequestMapping("/api/snapshots")` path
3. Ensure authentication is configured

### If Database Errors

Run the migration:
```bash
mysql -u anand -p algotrading < docs/sql/chart_analysis_migration.sql
```

Check tables exist:
```sql
SHOW TABLES LIKE '%snapshot%';
-- Should show: chart_snapshot, snapshot_comment, snapshot_like
```

## 📊 What Should Work Right Now

✅ **Working:**
- Stock analysis endpoint (fundamentals, news, correlation, social)
- Pattern validation endpoint (preview only)
- Snapshot CRUD endpoints
- Public snapshot feed
- Snapshot statistics

⚠️ **Partially Working:**
- Pattern validation uses mock OHLC data (need to integrate real data)
- Fundamentals/News use placeholder data (need MoneyControl/Zerodha integration)
- Social sentiment works only if public snapshots exist

❌ **Not Yet Implemented:**
- Frontend Snapshot button (need to add to UI)
- SnapshotCreationModal component (need to create)
- Like/Unlike with proper SnapshotLike tracking
- Comments section

## 🎯 Next Steps After Verification

1. ✅ Verify server starts without errors
2. ✅ Check logs for "Registered 2 AI validation tools"
3. ✅ Test `/api/snapshots/validate` endpoint
4. 📋 Create SnapshotCreationModal.tsx (last piece!)
5. 📋 Add Snapshot button to TVChartApp
6. 📋 Test end-to-end flow

## 💡 Pro Tips

- Use browser DevTools Network tab to see API calls
- Check server logs for validation details
- Start with `/validate` endpoint before `/create`
- Test with simple patterns first (trendlines) before complex ones (Elliott waves)

## 🆘 If Something's Wrong

**Share these with me:**
1. Server startup logs (first 50 lines)
2. Any ERROR or WARN messages
3. Output of: `curl http://localhost:8080/api/snapshots/stats`
4. Your Spring Boot version: `./gradlew --version`

I'll help debug immediately!

---

**Bottom line: If server started without errors and you don't see exceptions in logs, everything is likely working! The backend is ready. We just need the frontend modal component to complete the feature.** 🚀
