-- ============================================================
-- Elliott Screener + Trade Suggestion Tracker
-- ============================================================

-- 1. Elliott Screener config
CREATE TABLE IF NOT EXISTS elliott_screener (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT        NOT NULL,
    name                VARCHAR(255)  NOT NULL,
    symbols             MEDIUMTEXT    NOT NULL,
    timeframes          VARCHAR(255)  NOT NULL,
    primary_timeframe   VARCHAR(64),
    schedule_cron       VARCHAR(128)  NOT NULL,
    enabled             TINYINT(1)    NOT NULL DEFAULT 1,
    next_run_at         DATETIME(6),
    last_run_at         DATETIME(6),
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    UNIQUE KEY uq_es_user_name (user_id, name),
    INDEX idx_es_user_id (user_id),
    INDEX idx_es_enabled_next_run (enabled, next_run_at)
);

-- 2. Elliott Screener Run (audit per execution)
CREATE TABLE IF NOT EXISTS elliott_screener_run (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    screener_id         BIGINT        NOT NULL,
    status              VARCHAR(32)   NOT NULL,
    total_symbols       INT           NOT NULL DEFAULT 0,
    processed_symbols   INT           NOT NULL DEFAULT 0,
    suggestions_created INT           NOT NULL DEFAULT 0,
    duplicates_skipped  INT           NOT NULL DEFAULT 0,
    error_summary       MEDIUMTEXT,
    started_at          DATETIME(6)   NOT NULL,
    completed_at        DATETIME(6),
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    INDEX idx_esr_screener_id  (screener_id),
    INDEX idx_esr_started_at   (started_at),
    INDEX idx_esr_status       (status)
);

-- 3. Elliott Trade Suggestion
CREATE TABLE IF NOT EXISTS elliott_trade_suggestion (
    id                           BIGINT AUTO_INCREMENT PRIMARY KEY,
    screener_id                  BIGINT        NOT NULL,
    run_id                       BIGINT,
    user_id                      BIGINT        NOT NULL,
    symbol                       VARCHAR(128)  NOT NULL,
    direction                    VARCHAR(16)   NOT NULL,
    state                        VARCHAR(32)   NOT NULL,
    hypothesis_label             VARCHAR(512),
    wave_context                 VARCHAR(1024),
    pattern                      VARCHAR(512),
    current_stage                VARCHAR(128),
    entry_zone                   VARCHAR(256),
    stop_loss                    VARCHAR(256),
    target1                      VARCHAR(256),
    trigger_description          VARCHAR(1024),
    reasoning                    MEDIUMTEXT,
    confidence_layers_json       MEDIUMTEXT,
    invalidation_conditions_json MEDIUMTEXT,
    anomaly_flags_json           MEDIUMTEXT,
    raw_ai_response              MEDIUMTEXT,
    user_notes                   MEDIUMTEXT,
    proposed_at                  DATETIME(6),
    accepted_at                  DATETIME(6),
    activated_at                 DATETIME(6),
    closed_at                    DATETIME(6),
    created_at                   DATETIME(6)   NOT NULL,
    updated_at                   DATETIME(6)   NOT NULL,
    INDEX idx_ets_screener_id           (screener_id),
    INDEX idx_ets_user_id               (user_id),
    INDEX idx_ets_symbol_state          (symbol, state),
    INDEX idx_ets_screener_symbol_dir   (screener_id, symbol, direction, state),
    INDEX idx_ets_user_state            (user_id, state),
    INDEX idx_ets_run_id                (run_id)
);

-- 4. Suggestion Chart Layout (for multi-timeframe chart views)
CREATE TABLE IF NOT EXISTS suggestion_chart_layout (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    suggestion_id BIGINT NOT NULL,
    timeframe VARCHAR(64) NOT NULL,
    tab_order INT NOT NULL DEFAULT 0,
    overlays_json MEDIUMTEXT,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_scl_suggestion_tf (suggestion_id, timeframe),
    KEY idx_scl_suggestion_id (suggestion_id)
);

-- 5. Add timeframe columns to elliott_trade_suggestion
ALTER TABLE elliott_trade_suggestion
    ADD COLUMN primary_timeframe VARCHAR(64) NULL,
    ADD COLUMN all_timeframes VARCHAR(255) NULL;

-- Add numeric price fields for automated trade monitoring
ALTER TABLE elliott_trade_suggestion
    ADD COLUMN IF NOT EXISTS entry_low       DECIMAL(15,2) NULL,
    ADD COLUMN IF NOT EXISTS entry_high      DECIMAL(15,2) NULL,
    ADD COLUMN IF NOT EXISTS stop_loss_price DECIMAL(15,2) NULL,
    ADD COLUMN IF NOT EXISTS target1_price   DECIMAL(15,2) NULL;
