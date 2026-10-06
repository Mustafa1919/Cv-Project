package com.mstech.vitrin.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.mstech.vitrin.gateway.testing.TestSettings;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RateLimitPropertiesTests {
    @Test
    void exposesAllSixCodeDefaults() {
        RateLimitProperties properties = TestSettings.rates(Map.of());

        assertThat(properties.perMinute())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "public", 30,
                                "session-create", 10,
                                "session-refresh", 10,
                                "authenticated", 120,
                                "preflight", 60,
                                "session-read", 60));
    }

    @Test
    void overrideReplacesOnlyItsGroup() {
        RateLimitProperties properties = TestSettings.rates(Map.of("public", 7));

        assertThat(properties.limitFor("public")).isEqualTo(7);
        assertThat(properties.limitFor("session-create")).isEqualTo(10);
        assertThat(properties.limitFor("session-refresh")).isEqualTo(10);
        assertThat(properties.limitFor("authenticated")).isEqualTo(120);
        assertThat(properties.limitFor("preflight")).isEqualTo(60);
        assertThat(properties.limitFor("session-read")).isEqualTo(60);
    }

    @Test
    void unknownGroupFailsClosed() {
        RateLimitProperties properties = TestSettings.rates(Map.of());

        assertThatIllegalStateException().isThrownBy(() -> properties.limitFor("unknown"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveLimits(int limit) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TestSettings.rates(Map.of("public", limit)));
    }

    @ParameterizedTest
    @MethodSource("invalidDurations")
    void rejectsNonPositiveDurations(Duration timeout, Duration retry) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(null, Map.of(), timeout, retry, 100));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveFallbackCapacity(int capacity) {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new RateLimitProperties(
                                        null,
                                        Map.of(),
                                        Duration.ofMillis(250),
                                        Duration.ofSeconds(5),
                                        capacity));
    }

    static Stream<Arguments> invalidDurations() {
        return Stream.of(
                Arguments.of(Duration.ZERO, Duration.ofSeconds(5)),
                Arguments.of(Duration.ofMillis(-1), Duration.ofSeconds(5)),
                Arguments.of(Duration.ofMillis(250), Duration.ZERO),
                Arguments.of(Duration.ofMillis(250), Duration.ofSeconds(-1)));
    }
}
