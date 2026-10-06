package com.mstech.vitrin.platform.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Sends application log records to the OpenTelemetry log pipeline.
 *
 * <p>Spring Boot configures the exporter but nothing feeds it: Logback has to be bridged. The
 * bridge is attached only when a log endpoint is configured, so a service without a collector does
 * not buffer records it will never send. Console output is not affected.
 */
@AutoConfiguration(
        afterName =
                "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration")
@ConditionalOnClass({OpenTelemetry.class, OpenTelemetryAppender.class, LoggerContext.class})
@ConditionalOnProperty("management.opentelemetry.logging.export.otlp.endpoint")
public class OtlpLogBridgeAutoConfiguration {
    static final String APPENDER_NAME = "OTLP";

    @Bean
    @ConditionalOnBean(OpenTelemetry.class)
    OtlpLogBridge otlpLogBridge(OpenTelemetry openTelemetry) {
        return new OtlpLogBridge(openTelemetry);
    }

    /** Owns the appender so that a closed context stops feeding a closed exporter. */
    static final class OtlpLogBridge implements DisposableBean {
        private final Logger root;
        private final OpenTelemetryAppender appender;

        OtlpLogBridge(OpenTelemetry openTelemetry) {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (!(factory instanceof LoggerContext context)) {
                throw new IllegalStateException(
                        "The OTLP log bridge needs Logback, found " + factory.getClass().getName());
            }
            this.root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            this.appender = new OpenTelemetryAppender();
            this.appender.setName(APPENDER_NAME);
            this.appender.setContext(context);
            this.appender.setOpenTelemetry(openTelemetry);
            // Structured fields (the gateway's request record) become log attributes.
            this.appender.setCaptureKeyValuePairAttributes(true);
            this.appender.start();
            // A restarted context (tests, devtools) must not leave two bridges on the root logger.
            this.root.detachAppender(APPENDER_NAME);
            this.root.addAppender(this.appender);
        }

        @Override
        public void destroy() {
            this.root.detachAppender(this.appender);
            this.appender.stop();
        }
    }
}
