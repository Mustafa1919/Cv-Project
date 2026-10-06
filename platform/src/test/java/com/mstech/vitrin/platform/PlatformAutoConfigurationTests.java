package com.mstech.vitrin.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PlatformAutoConfigurationTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PlatformAutoConfiguration.class));

    @Test
    void providesUtcClock() {
        runner.run(
                context ->
                        assertThat(context.getBean(Clock.class).getZone())
                                .isEqualTo(ZoneOffset.UTC));
    }

    @Test
    void backsOffWhenClockIsProvided() {
        Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        runner.withBean(Clock.class, () -> fixed)
                .run(context -> assertThat(context.getBean(Clock.class)).isSameAs(fixed));
    }
}
