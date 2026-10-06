package com.mstech.vitrin.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mstech.vitrin.core.identity.TestKeyFiles;
import com.mstech.vitrin.platform.startup.StartupGuardException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class CoreStartupGuardTests {

    private static final String RANDOM_PORTS = "--server.port=0";
    private static final String RANDOM_MANAGEMENT_PORT = "--management.server.port=0";

    @Test
    void refusesToStartWithoutProfile() {
        assertRefused("dev, test, prod");
    }

    @Test
    void refusesProductionWithoutSigningKey() {
        assertRefused("'vitrin.identity.signing-key-file'", "--spring.profiles.active=prod");
    }

    @Test
    void refusesProductionWhenSigningKeyFileIsMissing(@TempDir Path dir) {
        assertRefused(
                "readable file",
                "--spring.profiles.active=prod",
                "--vitrin.identity.signing-key-file=" + dir.resolve("absent"));
    }

    @Test
    void startsInProductionWhenSigningKeyIsPresent(@TempDir Path dir) throws Exception {
        Path key = TestKeyFiles.writeSigningKey(dir);

        try (ConfigurableApplicationContext context =
                run("--spring.profiles.active=prod", "--vitrin.identity.signing-key-file=" + key)) {
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
        return new SpringApplicationBuilder(CoreApplication.class).run(all);
    }
}
