# Google OAuth Sign-In Setup Guide

## Implementation Complete ✅

Google Sign-In has been integrated into your trading platform. Follow these steps to configure it.

---

## Google Cloud Console Setup

### 1. Go to Google Cloud Console
Visit: https://console.cloud.google.com/apis/credentials

### 2. Create OAuth 2.0 Client ID

1. **Select your project** or create a new one
2. Click **"+ CREATE CREDENTIALS"** → **"OAuth client ID"**
3. If prompted, configure the **OAuth consent screen** first:
   - Choose **"External"** user type
   - Fill in required fields:
     - App name: `Your Trading Platform Name`
     - User support email: Your business email
     - Developer contact: Your business email
   - Add scopes: `email`, `profile`, `openid` (default scopes)
   - Save and continue

4. **Create OAuth Client ID:**
   - Application type: **Web application**
   - Name: `Trading Platform Web Client`

   - **Authorized JavaScript origins:**
     ```
     http://localhost:5173
     http://localhost:8080
     https://yourdomain.com
     ```

   - **Authorized redirect URIs:**
     ```
     http://localhost:5173
     http://localhost:8080
     https://yourdomain.com
     ```

   - Click **CREATE**

5. **Copy credentials:**
   - Client ID (looks like: `123456789-abcdefg.apps.googleusercontent.com`)
   - Client Secret (looks like: `GOCSPX-abcdefg123456`)

---

## Backend Configuration

### Set Environment Variables

Add these to your environment (or `.env` file):

```bash
# Google OAuth Configuration
export GOOGLE_OAUTH2_CLIENT_ID="YOUR_CLIENT_ID_HERE.apps.googleusercontent.com"
export GOOGLE_OAUTH2_CLIENT_SECRET="YOUR_CLIENT_SECRET_HERE"
```

**Or** update `application.properties` directly (not recommended for production):

```properties
google.oauth2.client-id=YOUR_CLIENT_ID_HERE.apps.googleusercontent.com
google.oauth2.client-secret=YOUR_CLIENT_SECRET_HERE
```

---

## Frontend Configuration

### Create `.env` file in `ui/chart-draw-app/` directory:

```bash
cd ui/chart-draw-app
cat > .env << 'EOF'
VITE_GOOGLE_OAUTH_CLIENT_ID=YOUR_CLIENT_ID_HERE.apps.googleusercontent.com
EOF
```

**Replace `YOUR_CLIENT_ID_HERE`** with your actual Google Client ID.

---

## Database Migration (Optional)

If you need to manually update the database schema, run:

```sql
-- Add new columns to users table
ALTER TABLE users
  ADD COLUMN email VARCHAR(255) UNIQUE,
  ADD COLUMN google_id VARCHAR(255) UNIQUE,
  ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
  MODIFY COLUMN password VARCHAR(255) NULL;

-- Add index for faster lookups
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_google_id ON users(google_id);
```

*Note: If you're using `spring.jpa.hibernate.ddl-auto=update`, this will be done automatically.*

---

## Testing the Integration

### 1. Start Backend:
```bash
./gradlew bootRun
```

### 2. Start Frontend:
```bash
cd ui/chart-draw-app
npm run dev
```

### 3. Test Google Sign-In:
1. Navigate to: http://localhost:5173/login
2. Click **"Continue with Google"** button
3. Select your Google account
4. You should be redirected to the dashboard

---

## How It Works

### Authentication Flow:

1. **User clicks "Continue with Google"**
2. **Google OAuth popup** appears for account selection
3. **Google returns ID token** to frontend
4. **Frontend sends ID token** to backend `/api/auth/google`
5. **Backend verifies token** with Google API
6. **Backend creates/updates user** in database:
   - New users: Auto-registered with `USER` role
   - Existing users: Logged in
7. **Backend returns JWT token**
8. **Frontend stores token** in localStorage
9. **User redirected to dashboard**

### User Data Stored:
- `googleId`: Unique Google user ID
- `email`: Google account email
- `username`: Generated from email (e.g., `john.doe` from `john.doe@gmail.com`)
- `provider`: `GOOGLE` (vs. `LOCAL` for password-based users)
- `role`: `USER` (can be upgraded to `MODERATOR` or `ADMIN` by admin)

---

## Security Notes

### ✅ What's Protected:
- Google ID tokens are verified server-side using Google's public keys
- Email verification is enforced (only verified Google emails allowed)
- JWT tokens are used for subsequent API calls
- CORS is configured to allow only trusted origins

### ⚠️ Important:
1. **Never commit** `.env` files or `application.properties` with secrets to git
2. **Use environment variables** in production
3. **Keep Client Secret secure** (only backend needs it)
4. **Rotate credentials** if compromised

---

## Troubleshooting

### Error: "Invalid Google ID token"
- Check that `GOOGLE_OAUTH2_CLIENT_ID` matches your Google Cloud Console Client ID
- Ensure the token hasn't expired (tokens are short-lived)

### Error: "Email already registered with different login method"
- User previously registered with username/password using same email
- Solution: Use original login method or admin can merge accounts

### Google button not appearing:
- Check browser console for errors
- Verify `VITE_GOOGLE_OAUTH_CLIENT_ID` is set in `.env`
- Ensure you've run `npm install @react-oauth/google`

### Database errors:
- Run the SQL migration script manually if auto-migration fails
- Check that MySQL user has `ALTER TABLE` privileges

---

## Files Modified/Created

### Backend:
- ✅ `build.gradle` - Added OAuth2 dependencies
- ✅ `User.java` - Added email, googleId, provider fields
- ✅ `UserRepository.java` - Added findByGoogleId, findByEmail methods
- ✅ `GoogleOAuth2Service.java` - Token verification service (NEW)
- ✅ `GoogleUserInfo.java` - DTO for Google user data (NEW)
- ✅ `AuthService.java` - Added authenticateWithGoogle method
- ✅ `AuthController.java` - Added POST /api/auth/google endpoint
- ✅ `application.properties` - Added Google OAuth config

### Frontend:
- ✅ `package.json` - Added @react-oauth/google dependency
- ✅ `AuthContext.tsx` - Added loginWithGoogle method
- ✅ `Login.tsx` - Added Google Sign-In button
- ✅ `main.tsx` - Wrapped app with GoogleOAuthProvider

---

## Production Checklist

Before deploying to production:

- [ ] Set `GOOGLE_OAUTH2_CLIENT_ID` environment variable
- [ ] Set `GOOGLE_OAUTH2_CLIENT_SECRET` environment variable
- [ ] Set `VITE_GOOGLE_OAUTH_CLIENT_ID` environment variable
- [ ] Add production domain to Google Cloud Console authorized origins
- [ ] Add production domain to authorized redirect URIs
- [ ] Test Google Sign-In on production domain
- [ ] Verify database migration completed successfully
- [ ] Monitor logs for authentication errors
- [ ] Set up monitoring for failed OAuth attempts

---

## Support

If you encounter issues:
1. Check backend logs for detailed error messages
2. Check browser console for frontend errors
3. Verify all environment variables are set correctly
4. Ensure Google Cloud Console configuration matches your domains

---

**Google Sign-In Integration Complete! 🎉**
