package com.mstech.vitrin.platform;

import com.mstech.vitrin.platform.startup.StartupGuard;
import com.mstech.vitrin.platform.startup.StartupGuardProperties;
import java.time.Clock;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Beans every service gets by depending on this module. */
@AutoConfiguration
@EnableConfigurationProperties(StartupGuardProperties.class)
public class PlatformAutoConfiguration {

    /** The only source of the current time; code takes this instead of calling {@code now()}. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Not conditional on purpose: a service cannot opt out of the startup check. */
    @Bean
    InitializingBean startupGuard(Environment environment, StartupGuardProperties properties) {
        return () -> StartupGuard.check(environment, properties);
    }
}
