-- Migration for Google OAuth Support
-- Run this script to update the users table for OAuth authentication

-- Step 1: Make password column nullable (for OAuth users)
ALTER TABLE users MODIFY COLUMN password VARCHAR(255) NULL;

-- Step 2: Add email column if doesn't exist
ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(255) UNIQUE;

-- Step 3: Add google_id column if doesn't exist
ALTER TABLE users ADD COLUMN IF NOT EXISTS google_id VARCHAR(255) UNIQUE;

-- Step 4: Add provider column if doesn't exist
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL';

-- Step 5: Add indexes for faster lookups
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_google_id ON users(google_id);
CREATE INDEX IF NOT EXISTS idx_users_provider ON users(provider);

-- Verification queries (uncomment to check)
-- DESCRIBE users;
-- SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'users' AND TABLE_SCHEMA = DATABASE();
