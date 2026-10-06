package com.mstech.vitrin.platform.startup;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Fails closed: a service starts only when exactly one environment profile is active and, in
 * production, everything it declared as required is present.
 */
public final class StartupGuard {

    public static final String DEVELOPMENT = "dev";
    public static final String TEST = "test";
    public static final String PRODUCTION = "prod";

    private static final Set<String> ENVIRONMENT_PROFILES = Set.of(DEVELOPMENT, TEST, PRODUCTION);

    private StartupGuard() {}

    public static void check(Environment environment, StartupGuardProperties properties) {
        List<String> active =
                Arrays.stream(environment.getActiveProfiles())
                        .filter(ENVIRONMENT_PROFILES::contains)
                        .sorted()
                        .toList();
        if (active.size() != 1) {
            throw new StartupGuardException(
                    "Exactly one of the profiles dev, test, prod must be active; found "
                            + active
                            + ".");
        }
        if (!active.contains(PRODUCTION)) {
            return;
        }
        for (String name : properties.requiredInProduction()) {
            requireValue(environment, name);
        }
        for (String name : properties.requiredFilesInProduction()) {
            requireReadableFile(name, requireValue(environment, name));
        }
    }

    private static String requireValue(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || !StringUtils.hasText(value)) {
            throw new StartupGuardException(
                    "Property '" + name + "' is required in production but has no value.");
        }
        return value;
    }

    private static void requireReadableFile(String name, String value) {
        boolean readable;
        try {
            Path path = Path.of(value);
            readable = Files.isRegularFile(path) && Files.isReadable(path);
        } catch (InvalidPathException e) {
            readable = false;
        }
        if (!readable) {
            // The value is not echoed: a misconfigured secret must not end up in the logs.
            throw new StartupGuardException(
                    "Property '" + name + "' must point to a readable file in production.");
        }
    }
}
