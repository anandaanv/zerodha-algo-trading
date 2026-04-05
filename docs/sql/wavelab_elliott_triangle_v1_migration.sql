-- WaveLab Elliott Triangle V1
-- Stores one run per triangle analysis request with A/B proposer outputs
-- and final evaluator verdict.

CREATE TABLE IF NOT EXISTS wle_triangle_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    timeframe VARCHAR(16) NOT NULL,
    candle_count INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    proposer_a VARCHAR(32),
    proposer_b VARCHAR(32),
    evaluator VARCHAR(32),
    input_summary_json MEDIUMTEXT,
    proposer_a_output_json MEDIUMTEXT,
    proposer_b_output_json MEDIUMTEXT,
    evaluator_output_json MEDIUMTEXT,
    final_triangle_type VARCHAR(64),
    final_status VARCHAR(32),
    final_confidence DOUBLE,
    selected_source VARCHAR(32),
    final_reason MEDIUMTEXT,
    error_message MEDIUMTEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_wle_triangle_user_created (user_id, created_at),
    INDEX idx_wle_triangle_symbol_tf (symbol, timeframe)
);
