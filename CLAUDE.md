# Project Guidelines

## Frontend API Calls — ALWAYS use getApiUrl + withAuth

Every backend API call from the React frontend **must**:
1. Build the URL using `getApiUrl(path)` from `src/config/api.ts` — this resolves the correct backend origin in both dev (Vite proxy) and production (nginx)
2. Include the auth header using `withAuth()` from `src/utils/apiHelper.ts` — without this, Spring Security returns 403

```ts
import { getApiUrl } from "../config/api";
import { withAuth } from "../utils/apiHelper";

const res = await fetch(getApiUrl("/api/ohlc?symbol=HDFCBANK&interval=FifteenMinute").toString(), withAuth());
```

**Never** use bare `fetch("/api/...")` or `fetch("http://localhost:8080/...")` — these break in production or drop the auth token.

### Why this matters
- `getApiUrl` uses `VITE_API_BASE_URL` in production builds (set in `.env.production` via `ui-deploy.sh`). Without it, the URL resolves to `window.location.origin` which may be the CloudFront/S3 domain, not the API server.
- `withAuth()` attaches `Authorization: Bearer <jwt>`. All `/api/**` endpoints (except `/api/auth/**`) require this header — missing it causes 403.

## Environment Configuration

The app is deployed as a **static build** (S3 + CloudFront). There is no Vite dev server in production.

| Environment | `VITE_API_BASE_URL` | Set in |
|-------------|---------------------|--------|
| Local dev   | `http://localhost:8080` | `.env.development` (committed) |
| Production  | `https://tradeapi.dheemantech.in` | `.env.production` (written by `ui-deploy.sh`) |

Never use relative `/api/...` URLs — they resolve to the frontend origin (CloudFront/S3), not the backend.

`ui-deploy.sh` writes `VITE_API_BASE_URL=https://tradeapi.dheemantech.in` into `.env.production` before building.

## CloudFront Invalidation

Always invalidate **only `/index.html`**, never `/*`:
```bash
aws cloudfront create-invalidation --distribution-id E3GNIWHAP6FDTM --paths "/index.html"
```

## Branch Policy — Never Work on Master

Never commit directly to `master`. If the current branch is `master`, stop and ask the user whether to create a new branch before making any changes.

## PR and Issue Policy — No Code Merged Without a PR + GitHub Issue

Every code change merged to `master` must have:
1. A **GitHub issue** describing what changed and why (bug, feature, or fix)
2. A **Pull Request** referencing that issue — never merge directly, always go through a PR

Create the issue first, then the PR. Reference the issue in the PR body (e.g. `Closes #33`).

## New Pages — Add to Dashboard

Any new top-level page that doesn't belong under an existing parent section **must** be linked from the Dashboard (`src/components/Dashboard.tsx`). This ensures all features are discoverable from the home screen.

## Kite OAuth Flow (Production)

- Frontend navigates to `/api/admin/kite-configs/1/connect` (NOT `/kite-login`)
- nginx routes `/api/*` to Tomcat; `/kite-login` is not proxied and will serve the React SPA
- Callback URL in Kite developer console must match the production domain
