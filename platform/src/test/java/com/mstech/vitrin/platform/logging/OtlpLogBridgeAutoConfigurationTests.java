package com.mstech.vitrin.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OtlpLogBridgeAutoConfigurationTests {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(OtlpLogBridgeAutoConfigurationTests.class);
    private static final String ENDPOINT =
            "management.opentelemetry.logging.export.otlp.endpoint=http://collector:4318/v1/logs";

    private final InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
    private final OpenTelemetrySdk sdk =
            OpenTelemetrySdk.builder()
                    .setTracerProvider(SdkTracerProvider.builder().build())
                    .setLoggerProvider(
                            SdkLoggerProvider.builder()
                                    .addLogRecordProcessor(
                                            SimpleLogRecordProcessor.create(exporter))
                                    .build())
                    .build();
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OtlpLogBridgeAutoConfiguration.class))
                    .withBean(OpenTelemetry.class, () -> sdk);

    @Test
    void recordsCarryTheMessageAndTheTraceOfTheCurrentSpan() {
        runner.withPropertyValues(ENDPOINT)
                .run(
                        context -> {
                            Span span = sdk.getTracer("test").spanBuilder("request").startSpan();
                            Scope scope = span.makeCurrent();
                            try {
                                LOGGER.atInfo()
                                        .addKeyValue("http.route", "session-create")
                                        .log("bridged {}", "record");
                            } finally {
                                scope.close();
                                span.end();
                            }

                            List<LogRecordData> records = exporter.getFinishedLogRecordItems();
                            assertThat(records)
                                    .filteredOn(
                                            record ->
                                                    "bridged record"
                                                            .equals(
                                                                    record.getBodyValue() == null
                                                                            ? null
                                                                            : record.getBodyValue()
                                                                                    .asString()))
                                    .singleElement()
                                    .satisfies(
                                            record -> {
                                                assertThat(
                                                                record.getAttributes()
                                                                        .get(
                                                                                io.opentelemetry.api
                                                                                        .common
                                                                                        .AttributeKey
                                                                                        .stringKey(
                                                                                                "http.route")))
                                                        .isEqualTo("session-create");
                                                assertThat(record.getSpanContext().getTraceId())
                                                        .isEqualTo(
                                                                span.getSpanContext().getTraceId());
                                                assertThat(record.getSpanContext().getSpanId())
                                                        .isEqualTo(
                                                                span.getSpanContext().getSpanId());
                                            });
                        });
    }

    @Test
    void nothingIsBridgedWithoutALogEndpoint() {
        runner.run(
                context -> {
                    assertThat(context)
                            .doesNotHaveBean(OtlpLogBridgeAutoConfiguration.OtlpLogBridge.class);
                    LOGGER.info("not bridged");
                    assertThat(exporter.getFinishedLogRecordItems()).isEmpty();
                    assertThat(rootAppender()).isNull();
                });
    }

    @Test
    void theBridgeIsRemovedWhenTheContextCloses() {
        runner.withPropertyValues(ENDPOINT).run(context -> assertThat(rootAppender()).isNotNull());

        assertThat(rootAppender()).isNull();
        LOGGER.info("after close");
        assertThat(exporter.getFinishedLogRecordItems())
                .noneMatch(
                        record ->
                                record.getBodyValue() != null
                                        && "after close".equals(record.getBodyValue().asString()));
    }

    private static Object rootAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        return context.getLogger(Logger.ROOT_LOGGER_NAME)
                .getAppender(OtlpLogBridgeAutoConfiguration.APPENDER_NAME);
    }
}
