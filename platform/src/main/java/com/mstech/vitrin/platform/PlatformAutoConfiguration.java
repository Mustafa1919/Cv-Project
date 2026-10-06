package com.mstech.vitrin.platform;

import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Beans every service gets by depending on this module. */
@AutoConfiguration
public class PlatformAutoConfiguration {

    /** The only source of the current time; code takes this instead of calling {@code now()}. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
