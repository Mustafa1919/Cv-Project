plugins {
    id("vitrin.spring-boot-service")
}

dependencies {
    implementation(platform(libs.spring.cloud.dependencies))

    implementation(project(":platform"))
    implementation(libs.spring.cloud.starter.gateway.server.webmvc)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.bucket4j.core)
    implementation(libs.bucket4j.lettuce)
    // Parses the key set served by core; verification itself lives in platform.
    implementation(libs.nimbus.jose.jwt)

    // The HTTP tests run the real core in the same JVM instead of a stand-in.
    testImplementation(project(":core"))
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
