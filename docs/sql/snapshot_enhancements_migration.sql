-- Migration: Snapshot Enhancements - Tags, Memory, Drafts
-- Date: 2026-03-19
-- Description: Add multi-tag support, conversation memory, snapshot drafts

-- 1. Update chart_snapshot table for tags and memory
ALTER TABLE chart_snapshot
  ADD COLUMN pattern_tags TEXT AFTER pattern_type COMMENT 'Comma-separated list of pattern tags',
  ADD COLUMN conversation_summary TEXT AFTER ai_analysis COMMENT 'Internalized memory from pre-commit conversation',
  ADD COLUMN parent_snapshot_id BIGINT AFTER layout_id COMMENT 'Reference to parent snapshot if this is a clone',
  ADD COLUMN clone_count INT DEFAULT 0 AFTER likes_count COMMENT 'Number of times this snapshot has been cloned';

-- Add index for parent lookup
CREATE INDEX idx_parent_snapshot ON chart_snapshot(parent_snapshot_id);

-- 2. Fix stock_analysis_cache character set issue
ALTER TABLE stock_analysis_cache
  MODIFY COLUMN analysis_data TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 3. Create snapshot_draft table for pre-commit conversation
CREATE TABLE IF NOT EXISTS snapshot_draft (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(255) NOT NULL COMMENT 'User creating the draft',
  symbol VARCHAR(100) NOT NULL COMMENT 'Trading symbol',
  timeframe VARCHAR(20) NOT NULL COMMENT 'Chart timeframe',
  chart_state_json LONGTEXT COMMENT 'Current chart state including drawings',
  conversation_history LONGTEXT COMMENT 'JSON array of conversation messages',
  pattern_tags TEXT COMMENT 'Comma-separated pattern tags',
  user_comment TEXT COMMENT 'Latest user comment',
  ai_feedback TEXT COMMENT 'Latest AI feedback',
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT or COMMITTED',
  visibility VARCHAR(20) DEFAULT 'PRIVATE' COMMENT 'Intended visibility after commit',
  perform_ai_validation BOOLEAN DEFAULT TRUE COMMENT 'Whether to perform AI validation',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_modified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  INDEX idx_username_status (username, status),
  INDEX idx_symbol (symbol),
  INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Stores snapshot drafts during pre-commit conversation phase';

-- 4. Create tag_validation_cache table for AI-validated tags
CREATE TABLE IF NOT EXISTS tag_validation_cache (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  original_tag VARCHAR(255) NOT NULL COMMENT 'Tag as entered by user',
  normalized_tag VARCHAR(255) NOT NULL COMMENT 'Corrected/normalized tag',
  validation_count INT DEFAULT 1 COMMENT 'Number of times this tag was validated',
  confidence DOUBLE DEFAULT 1.0 COMMENT 'AI confidence in normalization',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  UNIQUE KEY unique_original_tag (original_tag),
  INDEX idx_normalized_tag (normalized_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Caches AI-validated pattern tags to avoid repeated API calls';

-- Insert some common pre-validated tags
INSERT INTO tag_validation_cache (original_tag, normalized_tag, validation_count, confidence) VALUES
  ('head and shoulders', 'Head and Shoulders', 1, 1.0),
  ('inverse head and shoulders', 'Inverse Head and Shoulders', 1, 1.0),
  ('double top', 'Double Top', 1, 1.0),
  ('double bottom', 'Double Bottom', 1, 1.0),
  ('triangle', 'Triangle', 1, 1.0),
  ('ascending triangle', 'Ascending Triangle', 1, 1.0),
  ('descending triangle', 'Descending Triangle', 1, 1.0),
  ('symmetrical triangle', 'Symmetrical Triangle', 1, 1.0),
  ('wedge', 'Wedge', 1, 1.0),
  ('rising wedge', 'Rising Wedge', 1, 1.0),
  ('falling wedge', 'Falling Wedge', 1, 1.0),
  ('channel', 'Channel', 1, 1.0),
  ('support', 'Support', 1, 1.0),
  ('resistance', 'Resistance', 1, 1.0),
  ('support/resistance', 'Support/Resistance', 1, 1.0),
  ('trendline', 'Trendline', 1, 1.0),
  ('breakout', 'Breakout', 1, 1.0),
  ('breakdown', 'Breakdown', 1, 1.0),
  ('flag', 'Flag', 1, 1.0),
  ('pennant', 'Pennant', 1, 1.0),
  ('cup and handle', 'Cup and Handle', 1, 1.0),
  ('elliott wave', 'Elliott Wave', 1, 1.0),
  ('fibonacci', 'Fibonacci', 1, 1.0),
  ('harmonic pattern', 'Harmonic Pattern', 1, 1.0),
  ('gartley', 'Gartley', 1, 1.0),
  ('butterfly', 'Butterfly', 1, 1.0),
  ('bat', 'Bat', 1, 1.0),
  ('crab', 'Crab', 1, 1.0),
  ('consolidation', 'Consolidation', 1, 1.0),
  ('reversal', 'Reversal', 1, 1.0),
  ('continuation', 'Continuation', 1, 1.0)
ON DUPLICATE KEY UPDATE validation_count = validation_count + 1;

-- 5. Add comments to existing columns for documentation
ALTER TABLE chart_snapshot
  MODIFY COLUMN pattern_type VARCHAR(100) COMMENT 'Legacy single pattern field (deprecated, use pattern_tags)',
  MODIFY COLUMN ai_validation TEXT COMMENT 'AI validation result summary',
  MODIFY COLUMN ai_analysis TEXT COMMENT 'Detailed AI analysis',
  MODIFY COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE, PUBLIC, or GROUP';

COMMIT;
