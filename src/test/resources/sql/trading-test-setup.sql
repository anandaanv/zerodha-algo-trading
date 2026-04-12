-- Drop and recreate trade_order with current entity schema (old schema had parent_order_id NOT NULL)
DROP TABLE IF EXISTS trade_order;
CREATE TABLE trade_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    signal_id BIGINT,
    symbol VARCHAR(100),
    underlying_symbol VARCHAR(20),
    segment VARCHAR(10),
    direction VARCHAR(10),
    quantity INT,
    lot_size INT,
    entry_price DECIMAL(14,4),
    exit_price DECIMAL(14,4),
    entry_time DATETIME(6),
    exit_time DATETIME(6),
    status VARCHAR(10),
    exit_reason VARCHAR(30),
    realised_pnl DECIMAL(14,2),
    instrument_type VARCHAR(10),
    instrument_token BIGINT,
    strike DECIMAL(14,4),
    expiry DATE,
    paper_trade BOOLEAN DEFAULT TRUE,
    created_at DATETIME(6),
    updated_at DATETIME(6)
);

-- Create segment_config if not present
CREATE TABLE IF NOT EXISTS segment_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    segment VARCHAR(10) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    capital_pct DECIMAL(6,2),
    provider VARCHAR(20),
    notes TEXT,
    UNIQUE KEY uq_segment_config_symbol_segment (symbol, segment)
);
