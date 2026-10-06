plugins {
    id("vitrin.java-library")
}

dependencies {
    api(libs.jspecify)
    implementation(libs.spring.boot.autoconfigure)
    // Token verification is shared by core and the gateway; Nimbus types stay out of the API.
    implementation(libs.nimbus.jose.jwt)
    // Bridges Logback to the OpenTelemetry log pipeline that Spring Boot configures.
    implementation(libs.opentelemetry.logback.appender)
    implementation(libs.opentelemetry.api)
    implementation(libs.logback.classic)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.jqwik)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
}
