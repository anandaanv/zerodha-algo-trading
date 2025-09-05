package com.dtech.algo.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads application secrets (key/value) from the database and injects them
 * into the Spring Environment with highest precedence so that @Value and
 * @ConfigurationProperties can resolve them.
 *
 * Datasource connection is taken from environment (SPRING_DATASOURCE_* or spring.datasource.*).
 * Secrets are loaded for the current environment scope (SECRETS_ENV or spring.profiles.active; defaults to 'dev').
 */
public class DatabaseSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "database-secrets";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> props = loadFromDatabase(environment);
        if (!props.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
        }
    }

    private Map<String, Object> loadFromDatabase(ConfigurableEnvironment env) {
        Map<String, Object> map = new HashMap<>();

        String url = firstNonEmpty(env.getProperty("SPRING_DATASOURCE_URL"), env.getProperty("spring.datasource.url"));
        String user = firstNonEmpty(env.getProperty("SPRING_DATASOURCE_USERNAME"), env.getProperty("spring.datasource.username"));
        String pass = firstNonEmpty(env.getProperty("SPRING_DATASOURCE_PASSWORD"), env.getProperty("spring.datasource.password"));
        String scope = firstNonEmpty(env.getProperty("SECRETS_ENV"), env.getProperty("spring.profiles.active"), "dev");

        if (isBlank(url) || isBlank(user)) {
            log("DatabaseSecrets: datasource url/username not set; skipping DB secrets load");
            return map;
        }

        // Try to ensure MySQL driver is available in older driver names too
        try {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException ignore) {
                Class.forName("com.mysql.jdbc.Driver");
            }
        } catch (ClassNotFoundException ignore) {
            // DriverManager may still resolve the driver via ServiceLoader
        }

        try (Connection c = DriverManager.getConnection(url, user, pass);
             PreparedStatement ps = c.prepareStatement("SELECT prop_key, prop_value FROM app_secrets WHERE env = ?")) {
            ps.setString(1, scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String value = rs.getString(2);
                    map.put(key, value);
                }
            }
            log("DatabaseSecrets: loaded " + map.size() + " entries for env=" + scope);
        } catch (Exception e) {
            log("DatabaseSecrets: failed to load secrets from DB: " + e.getMessage());
        }

        return map;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (!isBlank(v)) return v;
        }
        return null;
    }

    private static void log(String msg) {
        System.out.println(msg);
    }

    @Override
    public int getOrder() {
        // Run AFTER ConfigDataEnvironmentPostProcessor so application.properties is already available
        return Ordered.LOWEST_PRECEDENCE;
    }
}
