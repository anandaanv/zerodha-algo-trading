# Project Guidelines

## Build System — Gradle

This project uses **Gradle** as its build system. Always use `./gradlew` for building, testing, and compiling:

```bash
./gradlew compileJava      # compile only
./gradlew build            # full build + tests
./gradlew bootRun          # run the app locally
```

Never use Maven (`mvn`) — there is no `pom.xml`.

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

## Commit Workflow — Branch → Issue → PR → Merge

Before committing any code change, follow this workflow:

1. **Ask the user**: is this a bugfix or a feature?
   - Bugfix → branch name: `bugfix/<short-description>` (e.g. `bugfix/connection-pool-exhaustion`)
   - Feature → branch name: `feature/<short-description>` (e.g. `feature/pattern-screener-ui`)
2. **Create a new branch** from `master` with the appropriate name
3. **Create a GitHub issue** describing what changed and why
4. **Commit** the changes referencing the issue
5. **Push** the branch and **create a PR** that closes the issue (e.g. `Closes #34`)
6. **Merge** the PR into master

Never skip any step. Never commit directly to `master`.

## New Pages — Add to Dashboard

Any new top-level page that doesn't belong under an existing parent section **must** be linked from the Dashboard (`src/components/Dashboard.tsx`). This ensures all features are discoverable from the home screen.

## Kite OAuth Flow (Production)

- Frontend navigates to `/api/admin/kite-configs/1/connect` (NOT `/kite-login`)
- nginx routes `/api/*` to Tomcat; `/kite-login` is not proxied and will serve the React SPA
- Callback URL in Kite developer console must match the production domain

## Local Backend Login Credentials

When you need a JWT from the local backend to test `/api/**` endpoints, read the credentials from `.local-dev-credentials` at the repo root (gitignored). Do NOT hardcode these credentials in scripts, commits, or memory files — always read from the file at use time. If the file is missing, ask the user before proceeding.

## Long-Running Commands

For any command that takes more than 2 minutes (simulations, batch CSV generation, model training), check progress every 5 minutes and report status to the user. Do not wait silently for extended periods.
