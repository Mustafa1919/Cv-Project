plugins {
    id("vitrin.java-conventions")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("spring-boot-starter-webmvc").get())
    implementation(libs.findLibrary("spring-boot-starter-actuator").get())
    implementation(libs.findLibrary("spring-boot-starter-opentelemetry").get())

    testImplementation(libs.findLibrary("spring-boot-starter-webmvc-test").get())
}
