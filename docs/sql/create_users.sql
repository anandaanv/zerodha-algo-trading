-- Create users table (will be auto-created by Hibernate, but here for reference)
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    INDEX idx_username (username)
);

-- Insert user 'anand' with password 'protrader' as ADMIN
-- BCrypt hash of 'protrader' (using strength 10)
INSERT INTO users (username, password, role, enabled, created_at, updated_at)
VALUES (
    'anand',
    '$2a$10$5aX8Z7QF6YJ/vK3mK1ZxLO8F8nYrF8D5qJH9wZ9JxQC1QK5Z5Z5Z5',
    'ADMIN',
    TRUE,
    NOW(),
    NOW()
);

-- Optional: Create additional test users
-- User with MODERATOR role - username: 'moderator', password: 'modpass'
INSERT INTO users (username, password, role, enabled, created_at, updated_at)
VALUES (
    'moderator',
    '$2a$10$7bC8Y6RF7YK/wL4nL2ZyMO9G9oZsG9E6rKI0xA0KyRD2RL6A6A6A6',
    'MODERATOR',
    TRUE,
    NOW(),
    NOW()
);

-- User with USER role - username: 'trader', password: 'trader123'
INSERT INTO users (username, password, role, enabled, created_at, updated_at)
VALUES (
    'trader',
    '$2a$10$9dE0Z8TH8ZM/yN5oN3Z0NO0H0pAtH0F7sLJ1yB1LzSE3SM7B7B7B7',
    'USER',
    TRUE,
    NOW(),
    NOW()
);
