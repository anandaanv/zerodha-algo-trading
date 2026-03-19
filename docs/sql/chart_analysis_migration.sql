-- Migration SQL for Chart Analysis & Snapshot Feature
-- Run this on your database to add new tables

-- 1. Chart Snapshot Table
-- Stores user chart snapshots with AI validation
CREATE TABLE IF NOT EXISTS chart_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    symbol VARCHAR(100) NOT NULL,
    timeframe VARCHAR(20) NOT NULL,
    layout_id BIGINT,
    snapshot_timestamp TIMESTAMP NOT NULL,
    start_date DATE,
    end_date DATE,
    chart_state_json TEXT,
    user_comment TEXT,
    pattern_type VARCHAR(100),
    ai_validation TEXT,
    ai_analysis TEXT,
    visibility ENUM('private', 'public', 'group') DEFAULT 'private',
    group_ids JSON,
    likes_count INT DEFAULT 0,
    views_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_symbol (symbol),
    INDEX idx_visibility (visibility),
    INDEX idx_created_at (created_at),
    FOREIGN KEY (layout_id) REFERENCES chart_layout(id) ON DELETE SET NULL
);

-- 2. Stock Analysis Cache Table
-- Caches external API calls for fundamentals, news, etc.
CREATE TABLE IF NOT EXISTS stock_analysis_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(100) NOT NULL,
    timeframe VARCHAR(20),
    analysis_type VARCHAR(50) NOT NULL,
    analysis_data JSON,
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    INDEX idx_symbol_type (symbol, analysis_type),
    INDEX idx_expires_at (expires_at)
);

-- 3. User Subscription Plan Table
-- Manages user subscription tiers and feature access
CREATE TABLE IF NOT EXISTS user_subscription_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    plan_type ENUM('free', 'basic', 'premium') DEFAULT 'free',
    private_snapshots_limit INT DEFAULT 10,
    group_sharing_enabled BOOLEAN DEFAULT FALSE,
    features_json JSON,
    valid_until TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username)
);

-- 4. Snapshot Comments Table
-- Stores comments on public snapshots
CREATE TABLE IF NOT EXISTS snapshot_comment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    username VARCHAR(255) NOT NULL,
    comment_text TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_snapshot_id (snapshot_id),
    INDEX idx_username (username),
    FOREIGN KEY (snapshot_id) REFERENCES chart_snapshot(id) ON DELETE CASCADE
);

-- 5. Snapshot Likes Table
-- Tracks which users liked which snapshots
CREATE TABLE IF NOT EXISTS snapshot_like (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    username VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY unique_user_snapshot (snapshot_id, username),
    FOREIGN KEY (snapshot_id) REFERENCES chart_snapshot(id) ON DELETE CASCADE
);

-- 6. Add default subscription plan for existing users (optional)
-- INSERT INTO user_subscription_plan (username, plan_type, private_snapshots_limit, group_sharing_enabled)
-- SELECT DISTINCT username, 'free', 10, FALSE
-- FROM chart_layout
-- WHERE username NOT IN (SELECT username FROM user_subscription_plan);

-- Verify the new structure
DESCRIBE chart_snapshot;
DESCRIBE stock_analysis_cache;
DESCRIBE user_subscription_plan;
DESCRIBE snapshot_comment;
DESCRIBE snapshot_like;
