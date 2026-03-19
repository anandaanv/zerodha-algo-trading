-- Manual migration SQL for chart layout refactoring
-- Run this on your remote database

-- Step 1: Create chart_layout table for symbol-agnostic layouts
CREATE TABLE IF NOT EXISTS chart_layout (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    layout_content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username)
);

-- Step 2: Backup existing user_chart_state data (just in case)
CREATE TABLE IF NOT EXISTS user_chart_state_backup_20251206 AS SELECT * FROM user_chart_state;

-- Step 3: Remove old columns from user_chart_state
ALTER TABLE user_chart_state
    DROP COLUMN IF EXISTS period,
    DROP COLUMN IF EXISTS layout_name,
    DROP COLUMN IF EXISTS meta_json;

-- Step 4: Add index for better performance on symbol + layout_id queries
CREATE INDEX IF NOT EXISTS idx_symbol_layout ON user_chart_state (symbol, layout_id);

-- Verify the new structure
DESCRIBE user_chart_state;
DESCRIBE chart_layout;

-- Expected user_chart_state columns: id, created_at, overlays_json, symbol, layout_id
-- Expected chart_layout columns: id, name, username, layout_content, created_at, updated_at
