-- Migration V2: Refactor user_chart_state to only store drawings (symbol + layoutId)

-- Backup existing data
CREATE TABLE IF NOT EXISTS user_chart_state_backup AS SELECT * FROM user_chart_state;

-- Check if columns exist before dropping them
SET @exist_period := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'user_chart_state' AND column_name = 'period');
SET @exist_layout_name := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'user_chart_state' AND column_name = 'layout_name');
SET @exist_meta_json := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'user_chart_state' AND column_name = 'meta_json');

SET @sql_drop_period := IF(@exist_period > 0, 'ALTER TABLE user_chart_state DROP COLUMN period', 'SELECT "Column period does not exist"');
SET @sql_drop_layout_name := IF(@exist_layout_name > 0, 'ALTER TABLE user_chart_state DROP COLUMN layout_name', 'SELECT "Column layout_name does not exist"');
SET @sql_drop_meta_json := IF(@exist_meta_json > 0, 'ALTER TABLE user_chart_state DROP COLUMN meta_json', 'SELECT "Column meta_json does not exist"');

PREPARE stmt_period FROM @sql_drop_period;
EXECUTE stmt_period;
DEALLOCATE PREPARE stmt_period;

PREPARE stmt_layout_name FROM @sql_drop_layout_name;
EXECUTE stmt_layout_name;
DEALLOCATE PREPARE stmt_layout_name;

PREPARE stmt_meta_json FROM @sql_drop_meta_json;
EXECUTE stmt_meta_json;
DEALLOCATE PREPARE stmt_meta_json;

-- Add index for better query performance if not exists
CREATE INDEX IF NOT EXISTS idx_symbol_layout ON user_chart_state (symbol, layout_id);
