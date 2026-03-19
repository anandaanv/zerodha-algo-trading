-- =====================================================
-- Dhan Integration Migration Script
-- =====================================================
-- Creates tables for Dhan broker integration
-- Run this after Phase 2 implementation

-- Create dhan_connect_settings table (singleton pattern, id=1)
CREATE TABLE IF NOT EXISTS dhan_connect_settings (
    id BIGINT PRIMARY KEY,
    client_id VARCHAR(255),
    user_id VARCHAR(255),
    access_token TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Insert default row with id=1 (singleton pattern)
INSERT INTO dhan_connect_settings (id, client_id, user_id, access_token)
VALUES (1, NULL, NULL, NULL)
ON DUPLICATE KEY UPDATE id=id;

-- Add Dhan credentials to app_secrets table (if using secrets management)
-- Uncomment and set actual values when deploying:
-- INSERT INTO app_secrets (env, prop_key, prop_value) VALUES ('dev', 'dhan.client.id', 'YOUR_DHAN_CLIENT_ID');
-- INSERT INTO app_secrets (env, prop_key, prop_value) VALUES ('dev', 'dhan.access.token', 'YOUR_DHAN_ACCESS_TOKEN');
-- INSERT INTO app_secrets (env, prop_key, prop_value) VALUES ('dev', 'dhan.user.id', 'YOUR_DHAN_USER_ID');

-- Create index for faster lookups (optional, since singleton)
CREATE INDEX idx_dhan_settings_client ON dhan_connect_settings(client_id);

-- Verify table creation
SELECT 'Dhan tables created successfully' AS status;
