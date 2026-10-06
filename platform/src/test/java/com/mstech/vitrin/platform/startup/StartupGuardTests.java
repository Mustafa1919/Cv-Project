package com.mstech.vitrin.platform.startup;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.platform.PlatformAutoConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StartupGuardTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PlatformAutoConfiguration.class))
                    .withPropertyValues(
                            "vitrin.guard.required-in-production=app.origin",
                            "vitrin.guard.required-files-in-production=app.key-file");

    @Test
    void refusesToStartWithoutProfile() {
        runner.run(context -> assertRefused(context.getStartupFailure(), "found []"));
    }

    @Test
    void refusesDevelopmentTogetherWithProduction() {
        profiles("dev", "prod")
                .run(context -> assertRefused(context.getStartupFailure(), "found [dev, prod]"));
    }

    @Test
    void ignoresProfilesThatAreNotEnvironments() {
        profiles("dev", "local").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startsInDevelopmentWithoutProductionSettings() {
        profiles("dev").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesProductionWithoutRequiredProperty(@TempDir Path dir) throws Exception {
        Path key = Files.writeString(dir.resolve("key"), "k");

        profiles("prod")
                .withPropertyValues("app.key-file=" + key)
                .run(context -> assertRefused(context.getStartupFailure(), "'app.origin'"));
    }

    @Test
    void refusesProductionWithBlankRequiredProperty(@TempDir Path dir) throws Exception {
        Path key = Files.writeString(dir.resolve("key"), "k");

        profiles("prod")
                .withPropertyValues("app.origin=  ", "app.key-file=" + key)
                .run(context -> assertRefused(context.getStartupFailure(), "'app.origin'"));
    }

    @Test
    void refusesProductionWithoutRequiredFileProperty() {
        profiles("prod")
                .withPropertyValues("app.origin=https://example.test")
                .run(context -> assertRefused(context.getStartupFailure(), "'app.key-file'"));
    }

    @Test
    void refusesProductionWhenRequiredFileIsMissing(@TempDir Path dir) {
        Path missing = dir.resolve("absent");

        profiles("prod")
                .withPropertyValues("app.origin=https://example.test", "app.key-file=" + missing)
                .run(
                        context -> {
                            assertRefused(context.getStartupFailure(), "readable file");
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .message()
                                    .doesNotContain(missing.toString());
                        });
    }

    @Test
    void startsInProductionWhenEverythingIsPresent(@TempDir Path dir) throws Exception {
        Path key = Files.writeString(dir.resolve("key"), "k");

        profiles("prod")
                .withPropertyValues("app.origin=https://example.test", "app.key-file=" + key)
                .run(context -> assertThat(context).hasNotFailed());
    }

    private ApplicationContextRunner profiles(String... profiles) {
        return runner.withInitializer(
                context -> context.getEnvironment().setActiveProfiles(profiles));
    }

    private static void assertRefused(@Nullable Throwable failure, String messagePart) {
        assertThat(failure)
                .rootCause()
                .isInstanceOf(StartupGuardException.class)
                .hasMessageContaining(messagePart);
    }
}
