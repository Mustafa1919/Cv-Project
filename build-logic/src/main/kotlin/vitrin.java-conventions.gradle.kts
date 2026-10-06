import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

group = "com.mstech.vitrin"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
    }
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
