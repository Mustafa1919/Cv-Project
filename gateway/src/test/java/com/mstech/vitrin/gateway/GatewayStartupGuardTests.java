package com.mstech.vitrin.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mstech.vitrin.platform.startup.StartupGuardException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class GatewayStartupGuardTests {

    private static final String RANDOM_PORTS = "--server.port=0";
    private static final String RANDOM_MANAGEMENT_PORT = "--management.server.port=0";

    @Test
    void refusesToStartWithoutProfile() {
        assertRefused("dev, test, prod");
    }

    @Test
    void refusesProductionWithoutSiteOrigin(@TempDir Path dir) throws Exception {
        Path key = Files.writeString(dir.resolve("key"), "k".repeat(32));

        assertRefused(
                "'vitrin.site.origin'",
                "--spring.profiles.active=prod",
                "--vitrin.ratelimit.address-hash-key-file=" + key);
    }

    @Test
    void refusesProductionWithoutAddressHashKey() {
        assertRefused(
                "'vitrin.ratelimit.address-hash-key-file'",
                "--spring.profiles.active=prod",
                "--vitrin.site.origin=https://example.test");
    }

    @Test
    void startsInProductionWhenEverythingIsPresent(@TempDir Path dir) throws Exception {
        Path key = Files.writeString(dir.resolve("key"), "k".repeat(32));

        try (ConfigurableApplicationContext context =
                run(
                        "--spring.profiles.active=prod",
                        "--vitrin.site.origin=https://example.test",
                        "--vitrin.ratelimit.address-hash-key-file=" + key)) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    private static void assertRefused(String messagePart, String... args) {
        assertThatThrownBy(() -> run(args).close())
                .rootCause()
                .isInstanceOf(StartupGuardException.class)
                .hasMessageContaining(messagePart);
    }

    private static ConfigurableApplicationContext run(String... args) {
        String[] all = new String[args.length + 2];
        all[0] = RANDOM_PORTS;
        all[1] = RANDOM_MANAGEMENT_PORT;
        System.arraycopy(args, 0, all, 2, args.length);
        return new SpringApplicationBuilder(GatewayApplication.class).run(all);
    }
}
