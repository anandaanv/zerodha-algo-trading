-- Migration to refactor chart layout architecture
-- Making layouts symbol-agnostic and drawings tied to symbol + layoutId

-- Step 1: Create new chart_layout table for symbol-agnostic layouts
CREATE TABLE IF NOT EXISTS chart_layout (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    layout_content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username)
);

-- Step 2: Backup existing user_chart_state data
CREATE TABLE user_chart_state_backup AS SELECT * FROM user_chart_state;

-- Step 3: Drop old constraints and columns from user_chart_state
ALTER TABLE user_chart_state
    DROP INDEX IF EXISTS symbol,
    DROP COLUMN period,
    DROP COLUMN layout_name,
    DROP COLUMN meta_json;

-- Step 4: Add layout_id column if not exists
ALTER TABLE user_chart_state
    ADD COLUMN IF NOT EXISTS layout_id BIGINT,
    ADD INDEX idx_symbol_layout (symbol, layout_id);

-- Step 5: Update entity - now user_chart_state only has:
-- id, symbol, layout_id, overlays_json, created_at
