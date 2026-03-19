-- SQL script to insert Dhan API credentials into app_secrets table
-- Replace placeholder values with your actual Dhan credentials

-- Dhan Client ID
INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('dev', 'dhan.client.id', 'YOUR_DHAN_CLIENT_ID')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_DHAN_CLIENT_ID';

-- Dhan Access Token (generate from Dhan web portal)
INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('dev', 'dhan.access.token', 'YOUR_DHAN_ACCESS_TOKEN')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_DHAN_ACCESS_TOKEN';

-- Dhan User ID
INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('dev', 'dhan.user.id', 'YOUR_DHAN_USER_ID')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_DHAN_USER_ID';

-- For production environment
INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('prod', 'dhan.client.id', 'YOUR_DHAN_CLIENT_ID_PROD')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_DHAN_CLIENT_ID_PROD';

INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('prod', 'dhan.access.token', 'YOUR_DHAN_ACCESS_TOKEN_PROD')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_DHAN_ACCESS_TOKEN_PROD';

INSERT INTO app_secrets (env, prop_key, prop_value)
VALUES ('prod', 'dhan.user.id', 'YOUR_DHAN_USER_ID_PROD')
ON DUPLICATE KEY UPDATE prop_value = 'YOUR_DHAN_USER_ID_PROD';

-- How to get Dhan credentials:
-- 1. Login to Dhan web portal: https://dhan.co
-- 2. Navigate to Settings > API Access
-- 3. Generate Access Token (note: Dhan tokens don't expire daily like Zerodha)
-- 4. Copy Client ID and User ID from your account settings
-- 5. Replace placeholders in this script with actual values
-- 6. Run this script against your database

-- Verify insertion:
SELECT * FROM app_secrets WHERE prop_key LIKE 'dhan.%';
