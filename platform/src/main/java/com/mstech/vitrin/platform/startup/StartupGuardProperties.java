package com.mstech.vitrin.platform.startup;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What a service needs before it may start in production.
 *
 * @param requiredInProduction names of properties that must have a value
 * @param requiredFilesInProduction names of properties that must point to a readable file
 */
@ConfigurationProperties("vitrin.guard")
public record StartupGuardProperties(
        @DefaultValue List<String> requiredInProduction,
        @DefaultValue List<String> requiredFilesInProduction) {

    public StartupGuardProperties {
        requiredInProduction = List.copyOf(requiredInProduction);
        requiredFilesInProduction = List.copyOf(requiredFilesInProduction);
    }
}
