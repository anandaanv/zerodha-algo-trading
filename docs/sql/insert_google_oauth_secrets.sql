-- Insert Google OAuth Credentials into app_secrets table
-- Replace 'YOUR_CLIENT_ID' and 'YOUR_CLIENT_SECRET' with actual values from Google Cloud Console

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

-- For 'prod' environment (if needed)
INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('prod', 'google.oauth2.client-id', 'YOUR_PROD_CLIENT_ID.apps.googleusercontent.com')
ON DUPLICATE KEY UPDATE
    prop_value = 'YOUR_PROD_CLIENT_ID.apps.googleusercontent.com',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('prod', 'google.oauth2.client-secret', 'YOUR_PROD_CLIENT_SECRET')
ON DUPLICATE KEY UPDATE
    prop_value = 'YOUR_PROD_CLIENT_SECRET',
    updated_at = CURRENT_TIMESTAMP;

-- Verify insertion
SELECT env, prop_key,
       CONCAT(LEFT(prop_value, 20), '...') as prop_value_preview,
       updated_at
FROM app_secrets
WHERE prop_key LIKE 'google.oauth2%'
ORDER BY env, prop_key;
