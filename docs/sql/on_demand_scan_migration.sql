-- On-Demand Elliott Scan tables

CREATE TABLE IF NOT EXISTS scan_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    max_scans_per_hour INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS scan_group_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_group_user (group_id, user_id)
);

CREATE TABLE IF NOT EXISTS user_scan_config (
    user_id BIGINT PRIMARY KEY,
    max_scans_per_hour INT NULL
);

CREATE TABLE IF NOT EXISTS on_demand_scan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(50) NOT NULL,
    primary_timeframe VARCHAR(20),
    all_timeframes VARCHAR(200),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    result_json LONGTEXT,
    error_message TEXT,
    INDEX idx_ods_user_id (user_id),
    INDEX idx_ods_requested_at (requested_at)
);

CREATE TABLE IF NOT EXISTS on_demand_scan_layout (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scan_id BIGINT NOT NULL,
    timeframe VARCHAR(20) NOT NULL,
    tab_order INT,
    overlays_json MEDIUMTEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_scan_tf (scan_id, timeframe),
    INDEX idx_odsl_scan_id (scan_id)
);
