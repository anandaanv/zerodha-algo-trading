-- Create table for Dhan authentication settings
-- This table stores Dhan API access token and client information
-- Singleton pattern - only one row with id=1

CREATE TABLE IF NOT EXISTS dhan_connect_settings (
    id BIGINT PRIMARY KEY,
    client_id VARCHAR(100),
    access_token VARCHAR(500),
    user_id VARCHAR(100),
    updated_at TIMESTAMP,
    CONSTRAINT chk_singleton CHECK (id = 1)
);

-- Create initial row if doesn't exist
INSERT INTO dhan_connect_settings (id, client_id, access_token, user_id, updated_at)
VALUES (1, NULL, NULL, NULL, NOW())
ON DUPLICATE KEY UPDATE id = id;

-- Add index on updated_at for monitoring
CREATE INDEX IF NOT EXISTS idx_dhan_updated_at ON dhan_connect_settings(updated_at);
