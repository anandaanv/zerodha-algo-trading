-- FinRL POC schema — isolated from main algotrading DB
-- Run: mysql -u anand -ppassword finrl_poc < schema.sql

USE finrl_poc;

CREATE TABLE IF NOT EXISTS rl_model (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    algorithm   VARCHAR(20)  NOT NULL,   -- PPO, A2C, DDPG etc
    symbols     TEXT,                    -- JSON array of symbols used
    timeframe   VARCHAR(20),
    train_start DATE,
    train_end   DATE,
    timesteps   INT,
    model_path  VARCHAR(255),
    notes       TEXT,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rl_episode (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    model_id    INT REFERENCES rl_model(id),
    phase       ENUM('train', 'eval', 'live') NOT NULL,
    start_date  DATE,
    end_date    DATE,
    total_return_pct DECIMAL(10,4),
    sharpe      DECIMAL(8,4),
    max_dd_pct  DECIMAL(8,4),
    n_trades    INT,
    win_rate    DECIMAL(6,4),
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rl_trade (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    episode_id      INT REFERENCES rl_episode(id),
    symbol          VARCHAR(30) NOT NULL,
    direction       ENUM('LONG', 'SHORT') NOT NULL,
    entry_time      DATETIME,
    exit_time       DATETIME,
    entry_price     DECIMAL(14,4),
    exit_price      DECIMAL(14,4),
    quantity        INT DEFAULT 1,
    pnl_pct         DECIMAL(10,4),
    exit_reason     VARCHAR(30),         -- TARGET, STOPLOSS, EOD, TIMEOUT
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
);
