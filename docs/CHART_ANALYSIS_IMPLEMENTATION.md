# Chart Analysis & Snapshot Feature - Implementation Guide

## Overview
This document describes the implementation of the Chart Analysis and Snapshot feature for the TVChartApp. This feature allows users to analyze stocks comprehensively and create validated chart snapshots with AI assistance.

## Phase 1: Analysis Panel ✅ COMPLETED

### Backend Implementation

#### Database Schema
Location: `docs/sql/chart_analysis_migration.sql`

Created 5 new tables:
1. **chart_snapshot** - Stores user chart snapshots with AI validation
2. **stock_analysis_cache** - Caches external API calls (fundamentals, news, etc.)
3. **user_subscription_plan** - Manages user subscription tiers
4. **snapshot_comment** - Comments on public snapshots
5. **snapshot_like** - Tracks snapshot likes

**To Deploy:**
```sql
mysql -u username -p database_name < docs/sql/chart_analysis_migration.sql
```

#### Java Entities Created
Location: `src/main/java/com/dtech/kitecon/data/`

1. `ChartSnapshot.java` - Chart snapshot entity
2. `StockAnalysisCache.java` - Analysis cache entity
3. `UserSubscriptionPlan.java` - Subscription plan entity
4. `SnapshotComment.java` - Comment entity
5. `SnapshotLike.java` - Like entity

#### Repository Interfaces
Location: `src/main/java/com/dtech/kitecon/repository/`

1. `ChartSnapshotRepository.java` - CRUD + search for snapshots
2. `StockAnalysisCacheRepository.java` - Cache management
3. `UserSubscriptionPlanRepository.java` - Subscription queries
4. `SnapshotCommentRepository.java` - Comment operations
5. `SnapshotLikeRepository.java` - Like operations

#### Service Layer
Location: `src/main/java/com/dtech/kitecon/service/`

**StockAnalysisService.java** - Main service providing:
- `analyzeStock(symbol, timeframe)` - Complete stock analysis
- `getFundamentals(symbol)` - Fundamentals with 24h cache
- `getNews(symbol)` - News with 15min cache
- `getCorrelation(symbol)` - Correlation with 1h cache
- `getSocialSentiment(symbol)` - Social sentiment from public snapshots
- `clearExpiredCache()` - Cache cleanup (for scheduled job)
- `invalidateCache(symbol)` - Manual cache invalidation

**Model:**
- `StockAnalysisResponse.java` - Complete response DTOs

#### Controller
Location: `src/main/java/com/dtech/kitecon/web/`

**StockAnalysisController.java** - REST endpoints:
- `POST /api/analysis/analyze` - Get complete analysis
- `GET /api/analysis/fundamentals?symbol=X` - Get fundamentals only
- `GET /api/analysis/news?symbol=X` - Get news only
- `GET /api/analysis/correlation?symbol=X` - Get correlation only
- `GET /api/analysis/social?symbol=X` - Get social sentiment only
- `DELETE /api/analysis/cache?symbol=X` - Clear cache

### Frontend Implementation

#### API Integration
Location: `ui/chart-draw-app/src/tradingview/analysisApi.ts`

TypeScript API client with functions:
- `analyzeStock(symbol, timeframe)` - Get complete analysis
- `getFundamentals(symbol)` - Get fundamentals
- `getNews(symbol)` - Get news
- `getCorrelation(symbol)` - Get correlation
- `getSocialSentiment(symbol)` - Get social sentiment
- `clearAnalysisCache(symbol)` - Clear cache

#### React Component
Location: `ui/chart-draw-app/src/tradingview/AnalysisPanel.tsx`

**AnalysisPanel Component:**
- Side panel that slides in from the right
- 4 tabs: Fundamentals, News, Correlation, Social
- Auto-fetches data when opened
- Refresh button to reload analysis
- Responsive design with styled components

**Features:**
- **Fundamentals Tab:** P/E ratio, market cap, EPS, dividend yield, sector, 52W high/low
- **News Tab:** Recent news with sentiment indicators
- **Correlation Tab:** NIFTY correlation, sector correlation, related stocks
- **Social Tab:** Community sentiment gauge, recent public snapshots with likes/comments

#### Integration with TVChartApp
Location: `ui/chart-draw-app/src/tradingview/TVChartApp.tsx`

**Changes:**
- Added `useState` for `isAnalysisPanelOpen`
- Added floating "Analyse" button (top-right)
- Button slides with panel for better UX
- Passes current symbol and timeframe to AnalysisPanel

### How to Use (Phase 1)

#### For Users:
1. Open any chart in TVChartApp
2. Click the "📊 Analyse" button in the top-right
3. View comprehensive stock analysis:
   - Fundamentals: Company metrics
   - News: Recent news with sentiment
   - Correlation: How stock moves with NIFTY/sector
   - Social: What other users are saying (public snapshots)
4. Click "Refresh Analysis" to update data

#### For Developers:

**Backend Setup:**
1. Run the migration SQL to create tables
2. Rebuild the Spring Boot application
3. Service will auto-cache API calls to reduce load

**Frontend Setup:**
1. No additional dependencies needed
2. Rebuild React app: `npm run build`
3. Component uses existing authentication

**To Integrate External APIs:**
Edit `StockAnalysisService.java`:
- `fetchFundamentalsFromExternalApi()` - Add Yahoo Finance/Zerodha integration
- `fetchNewsFromExternalApi()` - Add NewsAPI/Alpha Vantage integration
- `calculateCorrelation()` - Implement correlation calculation using historical data

## Phase 2: Snapshot Creation (TODO)

### Components to Create:

#### Backend:
1. **ChartSnapshotService.java** - Snapshot CRUD + AI validation
2. **AIPatternValidationService.java** - Pattern validation using OpenAI
3. **ChartSnapshotController.java** - Snapshot REST endpoints
4. **SubscriptionService.java** - Check user limits

#### Frontend:
1. **SnapshotCreationModal.tsx** - Modal for creating snapshots
2. **snapshotApi.ts** - API client for snapshots
3. Add "Snapshot" button next to "Analyse" button
4. Integrate with TradingView export APIs

### Features to Implement:
- Capture chart state (drawings, indicators)
- User annotation with pattern type selection
- AI validation of patterns (EDT, Elliott waves, triangles)
- Visibility control (private/public/group)
- Subscription limit checking

## Phase 3: Social Features (TODO)

### Components to Create:

#### Backend:
1. **SnapshotSocialService.java** - Likes, comments, feed
2. Update **StockAnalysisService.java** - Enhanced social sentiment

#### Frontend:
1. **SnapshotViewer.tsx** - View individual snapshots
2. **SnapshotFeed.tsx** - Browse public snapshots
3. **CommentSection.tsx** - Comment on snapshots
4. Add navigation to snapshot feed

### Features to Implement:
- Public snapshot feed with filters
- Like/unlike snapshots
- Comment on snapshots
- Share snapshots
- View user's snapshot history

## Phase 4: Subscription Management (TODO)

### Components to Create:

#### Backend:
1. **SubscriptionService.java** - Manage subscription plans
2. **SubscriptionController.java** - Subscription endpoints
3. Scheduled job to expire subscriptions

#### Frontend:
1. **SubscriptionModal.tsx** - Upgrade subscription UI
2. **SubscriptionBadge.tsx** - Show current plan
3. Limit enforcement in snapshot creation

### Features to Implement:
- Free tier: 10 private snapshots, no group sharing
- Basic tier: 100 private snapshots, group sharing
- Premium tier: Unlimited snapshots, all features
- Payment integration (optional)

## Testing Checklist

### Phase 1 (Analysis Panel):
- [ ] Run database migration successfully
- [ ] Backend compiles without errors
- [ ] Frontend compiles without errors
- [ ] "Analyse" button appears on chart
- [ ] Panel opens/closes smoothly
- [ ] All 4 tabs display data
- [ ] Refresh button works
- [ ] Cache is working (check logs)
- [ ] API authentication works

### Integration Points:

**External APIs to Integrate:**
1. **Fundamentals:** Yahoo Finance API, Zerodha Kite API
2. **News:** NewsAPI.org, Alpha Vantage News
3. **AI Validation:** OpenAI GPT-4 API, Claude API

**Authentication:**
- All endpoints use existing JWT authentication
- Uses `withAuth()` helper from `apiHelper.ts`

## File Structure Summary

```
backend/
├── src/main/java/com/dtech/kitecon/
│   ├── data/
│   │   ├── ChartSnapshot.java ✅
│   │   ├── StockAnalysisCache.java ✅
│   │   ├── UserSubscriptionPlan.java ✅
│   │   ├── SnapshotComment.java ✅
│   │   └── SnapshotLike.java ✅
│   ├── repository/
│   │   ├── ChartSnapshotRepository.java ✅
│   │   ├── StockAnalysisCacheRepository.java ✅
│   │   ├── UserSubscriptionPlanRepository.java ✅
│   │   ├── SnapshotCommentRepository.java ✅
│   │   └── SnapshotLikeRepository.java ✅
│   ├── service/
│   │   ├── StockAnalysisService.java ✅
│   │   └── model/
│   │       └── StockAnalysisResponse.java ✅
│   └── web/
│       └── StockAnalysisController.java ✅

frontend/
├── ui/chart-draw-app/src/tradingview/
│   ├── analysisApi.ts ✅
│   ├── AnalysisPanel.tsx ✅
│   └── TVChartApp.tsx (modified) ✅

database/
└── docs/sql/
    └── chart_analysis_migration.sql ✅
```

## Known Limitations (Current Implementation)

1. **Placeholder Data:**
   - Fundamentals, news, and correlation use mock data
   - Need to integrate real external APIs

2. **Social Sentiment:**
   - Works only with existing public snapshots
   - Need Phase 2 to create snapshots

3. **Cache Cleanup:**
   - No scheduled job yet for `clearExpiredCache()`
   - Add Spring @Scheduled task

4. **Error Handling:**
   - Basic error handling in place
   - Could be enhanced with retry logic

## Next Steps

1. **Deploy Phase 1:**
   - Run database migration
   - Build and deploy backend
   - Build and deploy frontend
   - Test the Analysis Panel

2. **Integrate External APIs:**
   - Sign up for NewsAPI, Alpha Vantage, etc.
   - Add API keys to application.properties
   - Implement actual data fetching

3. **Start Phase 2:**
   - Implement snapshot creation
   - Add AI validation service
   - Create snapshot modal

4. **Add Scheduled Jobs:**
   ```java
   @Scheduled(cron = "0 0 * * * *") // Every hour
   public void cleanupCache() {
       stockAnalysisService.clearExpiredCache();
   }
   ```

## Configuration

### application.properties (Backend)
```properties
# Cache TTL (already handled in code)
# External API keys (to be added)
newsapi.key=YOUR_KEY_HERE
alphavantage.key=YOUR_KEY_HERE
openai.key=YOUR_KEY_HERE
```

### Environment Variables (Frontend)
```bash
VITE_API_BASE_URL=http://localhost:8080
```

## Support and Questions

For implementation questions or issues:
1. Check logs for errors
2. Verify database migration ran successfully
3. Ensure authentication is working
4. Test API endpoints with Postman/curl

## Success Criteria

Phase 1 is successful when:
- ✅ User can click "Analyse" button
- ✅ Panel opens smoothly
- ✅ All 4 tabs display data
- ✅ Data is cached properly
- ✅ No errors in console/logs
