package com.trustguard.api.admin;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Criterion 2 / RULING 24: proves the seed settings in the real YAML files. seed.required is a fail-closed
 * setting, so a typo, a wrong value or a duplicate top-level key must fail a test, not production startup.
 * Placeholders are not resolved here: the raw values are asserted. No Spring context.
 */
class AdminSeedConfigTest {

    private static final String BASE_FILE = "application.yml";
    private static final String PRODUCTION_FILE = "application-production.yml";
    private static final String PREFIX = "trustguard.admin.seed.";

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    private List<PropertySource<?>> load(String file) throws IOException {
        return loader.load(file, new ClassPathResource(file));
    }

    private String property(String file, String key) throws IOException {
        for (PropertySource<?> source : load(file)) {
            Object value = source.getProperty(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    @Test
    void both_yaml_files_parse() throws IOException {
        assertFalse(load(BASE_FILE).isEmpty());
        assertFalse(load(PRODUCTION_FILE).isEmpty());
    }

    @Test
    void production_profile_requires_the_seed() throws IOException {
        assertEquals("true", property(PRODUCTION_FILE, PREFIX + "required"));
    }

    @Test
    void base_config_does_not_require_the_seed() throws IOException {
        assertEquals("false", property(BASE_FILE, PREFIX + "required"));
    }

    @Test
    void base_config_binds_the_seed_credentials_to_env_vars_with_no_default() throws IOException {
        assertEquals("${ADMIN_SEED_USERNAME:}", property(BASE_FILE, PREFIX + "username"));
        assertEquals("${ADMIN_SEED_PASSWORD:}", property(BASE_FILE, PREFIX + "password"));
    }
}