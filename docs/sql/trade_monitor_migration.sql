-- ============================================================
-- Trade Monitor Schema
-- Tables are auto-created by Hibernate (ddl-auto=update).
-- This file is a reference for manual review / MySQL Workbench.
-- ============================================================

-- trade_signal: every pattern scanner output that should be watched
CREATE TABLE IF NOT EXISTS trade_signal (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    symbol              VARCHAR(20)   NOT NULL,
    instrument_token    BIGINT,
    exchange            VARCHAR(10),
    instrument_type     VARCHAR(10),                    -- EQ, FUT
    expiry_date         DATE,
    lot_size            INT,
    direction           VARCHAR(10)   NOT NULL,         -- LONG, SHORT
    pattern_type        VARCHAR(40),
    confirmation_type   VARCHAR(10),
    entry_price         DECIMAL(14,4),
    stop_loss           DECIMAL(14,4),
    target              DECIMAL(14,4),
    neckline            DECIMAL(14,4),
    pattern_height      DECIMAL(14,4),
    rr_ratio            DECIMAL(6,2),
    rsi_divergence      TINYINT(1),
    daily_rsi           DECIMAL(6,2),
    stoch_rsi_k         DECIMAL(8,4),
    timeframe           VARCHAR(10),
    signal_time         DATETIME(6),
    entry_valid_until   DATETIME(6),
    status              VARCHAR(20)   NOT NULL,         -- see TradeStatus enum
    notes               TEXT,
    created_at          DATETIME(6),
    updated_at          DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_ts_status      (status),
    INDEX idx_ts_symbol      (symbol),
    INDEX idx_ts_signal_time (signal_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- trade_execution: one row per signal, created when entry fires
CREATE TABLE IF NOT EXISTS trade_execution (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    signal_id            BIGINT        NOT NULL UNIQUE,
    entry_order_id       VARCHAR(50),
    entry_price_actual   DECIMAL(14,4),
    entry_time           DATETIME(6),
    quantity             INT,
    notional_value       DECIMAL(14,2),
    margin_deployed      DECIMAL(14,2),
    sl_order_id          VARCHAR(50),
    target_order_id      VARCHAR(50),
    exit_order_id        VARCHAR(50),
    exit_price_actual    DECIMAL(14,4),
    exit_time            DATETIME(6),
    exit_reason          VARCHAR(20),                   -- see ExitReason enum
    gross_pnl_inr        DECIMAL(14,2),
    brokerage_inr        DECIMAL(10,2),
    net_pnl_inr          DECIMAL(14,2),
    net_pnl_pct          DECIMAL(8,4),                  -- % of margin_deployed
    created_at           DATETIME(6),
    updated_at           DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_te_signal_id (signal_id),
    CONSTRAINT fk_te_signal FOREIGN KEY (signal_id) REFERENCES trade_signal (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- trade_monitor_log: audit trail of every 5-min worker tick
CREATE TABLE IF NOT EXISTS trade_monitor_log (
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    signal_id                BIGINT        NOT NULL,
    checked_at               DATETIME(6)   NOT NULL,
    current_price            DECIMAL(14,4),
    unrealised_pnl_inr       DECIMAL(14,2),
    unrealised_pnl_pct       DECIMAL(8,4),
    candle_pattern_detected  VARCHAR(40),
    action_taken             VARCHAR(30)   NOT NULL,    -- see MonitorAction enum
    action_detail            VARCHAR(300),
    worker_version           VARCHAR(20),
    dry_run                  TINYINT(1),
    PRIMARY KEY (id),
    INDEX idx_tml_signal_id  (signal_id),
    INDEX idx_tml_checked_at (checked_at),
    CONSTRAINT fk_tml_signal FOREIGN KEY (signal_id) REFERENCES trade_signal (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ── Useful queries ──────────────────────────────────────────

-- All open positions
SELECT s.id, s.symbol, s.direction, s.pattern_type, s.entry_price, s.stop_loss, s.target,
       e.entry_price_actual, e.entry_time, e.margin_deployed
FROM   trade_signal s
JOIN   trade_execution e ON e.signal_id = s.id
WHERE  s.status = 'ACTIVE';

-- P&L summary by symbol (completed trades)
SELECT s.symbol,
       COUNT(*)                              AS trades,
       SUM(CASE WHEN e.net_pnl_inr > 0 THEN 1 ELSE 0 END) AS wins,
       SUM(e.net_pnl_inr)                   AS total_net_pnl,
       AVG(e.net_pnl_pct)                   AS avg_pnl_pct,
       SUM(e.brokerage_inr)                 AS total_brokerage
FROM   trade_signal s
JOIN   trade_execution e ON e.signal_id = s.id
WHERE  s.status = 'COMPLETED'
GROUP  BY s.symbol
ORDER  BY total_net_pnl DESC;

-- Recent monitor log for a specific signal
SELECT l.checked_at, l.current_price, l.unrealised_pnl_inr, l.action_taken, l.action_detail
FROM   trade_monitor_log l
WHERE  l.signal_id = :signalId
ORDER  BY l.checked_at DESC
LIMIT  20;

-- Insert a sample dry-run signal (RELIANCE DOUBLE_BOTTOM C1)
INSERT INTO trade_signal (
    symbol, instrument_token, exchange, instrument_type, lot_size, direction,
    pattern_type, confirmation_type, entry_price, stop_loss, target,
    neckline, pattern_height, rr_ratio, rsi_divergence, daily_rsi,
    timeframe, signal_time, entry_valid_until, status
) VALUES (
    'RELIANCE', 738561, 'NSE', 'EQ', 1, 'LONG',
    'DOUBLE_BOTTOM', 'C1', 1375.00, 1355.00, 1415.00,
    1395.00, 40.00, 2.00, 1, 55.0,
    '1h', NOW(), DATE_ADD(NOW(), INTERVAL 4 HOUR), 'WATCHING_ENTRY'
);
