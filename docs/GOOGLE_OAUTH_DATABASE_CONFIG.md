# Google OAuth Database Configuration

## Overview

Google OAuth credentials are now stored in the `app_secrets` table, following the same pattern as Kite API credentials.

---

## Insert Credentials into Database

### Step 1: Get Google OAuth Credentials

1. Go to [Google Cloud Console](https://console.cloud.google.com/apis/credentials)
2. Create OAuth 2.0 Client ID (if not already created)
3. Copy:
   - **Client ID** (looks like: `123456789-abc.apps.googleusercontent.com`)
   - **Client Secret** (looks like: `GOCSPX-abc123def456`)

### Step 2: Insert into Database

Run the following SQL (replace with your actual values):

```sql
-- For 'dev' environment
INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('dev', 'google.oauth2.client-id', 'YOUR_CLIENT_ID.apps.googleusercontent.com')
ON DUPLICATE KEY UPDATE
    prop_value = 'YOUR_CLIENT_ID.apps.googleusercontent.com',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('dev', 'google.oauth2.client-secret', 'YOUR_CLIENT_SECRET')
ON DUPLICATE KEY UPDATE
    prop_value = 'YOUR_CLIENT_SECRET',
    updated_at = CURRENT_TIMESTAMP;
```

**Quick Command:**
```bash
# Replace YOUR_CLIENT_ID and YOUR_CLIENT_SECRET with actual values
mysql -uanand -ppassword algotrading << EOF
INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('dev', 'google.oauth2.client-id', 'YOUR_CLIENT_ID.apps.googleusercontent.com')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_CLIENT_ID.apps.googleusercontent.com';

INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('dev', 'google.oauth2.client-secret', 'YOUR_CLIENT_SECRET')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_CLIENT_SECRET';
EOF
```

### Step 3: Verify

```sql
SELECT env, prop_key,
       CONCAT(LEFT(prop_value, 20), '...') as prop_value_preview,
       updated_at
FROM app_secrets
WHERE prop_key LIKE 'google.oauth2%'
ORDER BY env, prop_key;
```

---

## Configuration Priority

The `GoogleOAuth2Service` loads credentials in this order:

1. **Environment Variable**: `GOOGLE_OAUTH2_CLIENT_ID` (highest priority)
2. **Database**: `app_secrets` table (fallback)
3. **application.properties**: Placeholder only (lowest priority)

---

## Environment Selection

The system uses `SECRETS_ENV` environment variable to determine which environment's secrets to load:

- **Development**: `SECRETS_ENV=dev` (default)
- **Production**: `SECRETS_ENV=prod`

Set it in your environment:
```bash
export SECRETS_ENV=dev  # or prod
```

---

## Frontend Configuration

The frontend still needs the Client ID in `.env` file:

**`ui/chart-draw-app/.env`:**
```bash
VITE_GOOGLE_OAUTH_CLIENT_ID=YOUR_CLIENT_ID.apps.googleusercontent.com
```

**Why?** The frontend uses Google's JavaScript SDK, which requires the Client ID on the client side. This is safe because Client ID is not secret (it's publicly visible in OAuth flow).

---

## Production Setup

For production, insert credentials with `env='prod'`:

```sql
INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('prod', 'google.oauth2.client-id', 'YOUR_PROD_CLIENT_ID.apps.googleusercontent.com')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_PROD_CLIENT_ID.apps.googleusercontent.com';

INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('prod', 'google.oauth2.client-secret', 'YOUR_PROD_CLIENT_SECRET')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_PROD_CLIENT_SECRET';
```

Then set environment variable:
```bash
export SECRETS_ENV=prod
```

---

## Testing

### 1. Check Backend Logs

After starting the application, you should see:
```
INFO  c.d.k.auth.GoogleOAuth2Service - Loaded Google OAuth2 Client ID from database for env: dev
INFO  c.d.k.auth.GoogleOAuth2Service - Google OAuth2 Client ID loaded successfully
```

### 2. Test Google Sign-In

1. Navigate to: `http://localhost:8080/login` (or frontend URL)
2. Click "Continue with Google"
3. Sign in with Google account
4. Should redirect to dashboard

---

## Troubleshooting

### Error: "Google OAuth2 Client ID not configured"

**Cause**: No Client ID found in database or environment variables

**Solution:**
1. Check database:
   ```sql
   SELECT * FROM app_secrets WHERE prop_key LIKE 'google.oauth2%';
   ```
2. Verify `SECRETS_ENV` matches the `env` in database (default: `dev`)
3. Insert credentials using SQL above

### Error: "Invalid Google ID token"

**Cause**: Client ID mismatch between frontend and backend

**Solution:**
1. Ensure frontend `.env` has same Client ID as backend
2. Verify Client ID in Google Cloud Console matches

### Error: "Email already registered with different login method"

**Cause**: User previously registered with username/password using same email

**Solution:**
- Use original login method, OR
- Admin can manually merge accounts in database

---

## Security Notes

### Client ID
- ✅ **Safe to expose**: Client ID is public (visible in OAuth flow)
- ✅ Stored in database for convenience
- ✅ Can be in version control (if needed)

### Client Secret
- ⚠️ **KEEP SECRET**: Never expose or commit to version control
- ⚠️ Only used by backend (never sent to frontend)
- ⚠️ Stored in database with restricted access

### Database Security
- Ensure `app_secrets` table has restricted access
- Use database user permissions to limit who can read secrets
- Consider encrypting `prop_value` column (advanced)

---

## Migration from Environment Variables

If you previously had credentials in environment variables or `application.properties`:

### Old Way (Still Works):
```bash
export GOOGLE_OAUTH2_CLIENT_ID="..."
export GOOGLE_OAUTH2_CLIENT_SECRET="..."
```

### New Way (Recommended):
Store in `app_secrets` table as shown above. Environment variables still take priority if set.

---

## Complete Example

```bash
# 1. Insert credentials into database
mysql -uanand -ppassword algotrading << EOF
INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES
  ('dev', 'google.oauth2.client-id', '123456-abc.apps.googleusercontent.com'),
  ('dev', 'google.oauth2.client-secret', 'GOCSPX-abc123')
ON DUPLICATE KEY UPDATE
  prop_value = VALUES(prop_value);
EOF

# 2. Update frontend .env
echo "VITE_GOOGLE_OAUTH_CLIENT_ID=123456-abc.apps.googleusercontent.com" > ui/chart-draw-app/.env

# 3. Set environment (optional, defaults to 'dev')
export SECRETS_ENV=dev

# 4. Start backend
./gradlew bootRun

# 5. Start frontend (in another terminal)
cd ui/chart-draw-app
npm run dev
```

---

## Summary

✅ **Backend**: Loads Client ID from `app_secrets` table (or environment variable)
✅ **Frontend**: Requires Client ID in `.env` file
✅ **Priority**: Environment variable > Database > application.properties
✅ **Security**: Client Secret never sent to frontend
✅ **Flexibility**: Can switch between dev/prod using `SECRETS_ENV`

---

**Google OAuth Database Configuration Complete!** 🎉
