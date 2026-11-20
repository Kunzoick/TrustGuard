package com.trustguard.infrastructure.config;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rulings 2, 91 and 128: each of the three limits is validated at startup, and a bad value stops the application
 * with a message that names the property and never contains the value.
 */
class RequestLimitPropertiesTest {

    private static final String PREFIX = "trustguard.request-limits.";
    private static final String SIGNALS = "signals-bytes";
    private static final String DEFAULT = "default-bytes";
    private static final String ADMIN = "admin-bytes";
    private static final List<String> PROPERTIES = List.of(SIGNALS, DEFAULT, ADMIN);
    private static final int VALID_SIGNALS = 65_536;
    private static final int VALID_DEFAULT = 16_384;
    private static final int VALID_ADMIN = 32_768;

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RequestLimitProperties.class)
    static class PropertiesConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    private static String valid(String property) {
        return switch (property) {
            case SIGNALS -> String.valueOf(VALID_SIGNALS);
            case DEFAULT -> String.valueOf(VALID_DEFAULT);
            default -> String.valueOf(VALID_ADMIN);
        };
    }

    /** All three properties valid, except that the named one gets the given value (or is left out if null). */
    private static String[] valuesWith(String property, String value) {
        List<String> values = new ArrayList<>();
        for (String name : PROPERTIES) {
            if (name.equals(property)) {
                if (value != null) {
                    values.add(PREFIX + name + "=" + value);
                }
            } else {
                values.add(PREFIX + name + "=" + valid(name));
            }
        }
        return values.toArray(new String[0]);
    }

    static Stream<Arguments> invalidValues() {
        List<Arguments> cases = new ArrayList<>();
        for (String property : PROPERTIES) {
            for (String value : List.of("0", "-1", String.valueOf(Integer.MAX_VALUE))) {
                cases.add(Arguments.of(property, value));
            }
        }
        return cases.stream();
    }

    @Test
    @DisplayName("three valid limits bind")
    void validLimitsBind() {
        runner.withPropertyValues(valuesWith(ADMIN, valid(ADMIN))).run(context -> {
            assertThat(context).hasNotFailed();
            RequestLimitProperties limits = context.getBean(RequestLimitProperties.class);
            assertThat(limits.signalsBytes()).isEqualTo(VALID_SIGNALS);
            assertThat(limits.defaultBytes()).isEqualTo(VALID_DEFAULT);
            assertThat(limits.adminBytes()).isEqualTo(VALID_ADMIN);
        });
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidValues")
    @DisplayName("zero, negative and Integer.MAX_VALUE stop startup, name the property and never show the value")
    void invalidLimitsStopStartup(String property, String value) {
        runner.withPropertyValues(valuesWith(property, value)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure().rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(PREFIX + property)
                    .hasMessageNotContaining(value);
        });
    }

    @ParameterizedTest(name = "{0} missing")
    @ValueSource(strings = {SIGNALS, DEFAULT, ADMIN})
    @DisplayName("a missing limit binds to zero and stops startup, naming the property")
    void aMissingLimitStopsStartup(String property) {
        runner.withPropertyValues(valuesWith(property, null)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure().rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(PREFIX + property);
        });
    }

    @Test
    @DisplayName("the constructor applies the same rules and accepts the largest allowed limit")
    void theConstructorValidates() {
        int largest = Integer.MAX_VALUE - 1;

        assertThat(new RequestLimitProperties(1, largest, 1).defaultBytes()).isEqualTo(largest);
        assertThatThrownBy(() -> new RequestLimitProperties(0, 1, 1)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREFIX + SIGNALS);
        assertThatThrownBy(() -> new RequestLimitProperties(1, -1, 1)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREFIX + DEFAULT);
        assertThatThrownBy(() -> new RequestLimitProperties(1, 1, Integer.MAX_VALUE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(PREFIX + ADMIN);
    }
}