-- Secrets table to store key/value properties per environment scope
CREATE TABLE IF NOT EXISTS app_secrets (
  id BIGINT NOT NULL AUTO_INCREMENT,
  env VARCHAR(32) NOT NULL,
  prop_key VARCHAR(191) NOT NULL,
  prop_value TEXT NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_env_key (env, prop_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Example seed for 'dev' environment (replace CHANGE_ME with real values)
INSERT INTO app_secrets (env, prop_key, prop_value) VALUES
('dev', 'kite.api.key', 'CHANGE_ME'),
('dev', 'kite.api.user', 'CHANGE_ME'),
('dev', 'kite.api.secret', 'CHANGE_ME'),

('dev', 'openai.baseUrl', 'https://api.openai.com/v1'),
('dev', 'openai.apiKey', 'CHANGE_ME'),
('dev', 'openai.key', 'CHANGE_ME'),
('dev', 'openai.model', 'gpt-4o-mini'),
('dev', 'openai.base-url', 'https://api.openai.com/v1'),

('dev', 'whatsapp.token', 'CHANGE_ME'),
('dev', 'whatsapp.recipients', 'CHANGE_ME'), -- comma-separated numbers if needed
('dev', 'whatsapp.phone-number-id', 'CHANGE_ME'),
('dev', 'whatsapp.graph.api.version', 'v22.0'),

('dev', 'charts.output.directory', '/tmp/charts'),
('dev', 'charts.temp.directory', '/tmp/charts/temp'),
('dev', 'charts.use.tradingview', 'true'),
('dev', 'charts.browser.pool.size', '3'),
('dev', 'charts.browser.timeout', '30'),
('dev', 'patterns.trendlines.enabled', 'true'),
('dev', 'charts.visibleBars.default', '1000');

-- To use a different scope, insert the same keys with env='prod' (or set SECRETS_ENV to match)
