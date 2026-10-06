import com.diffplug.spotless.LineEnding
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import net.ltgt.gradle.errorprone.errorprone
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("net.ltgt.errorprone")
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
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

    errorprone(libs.findLibrary("errorprone-core").get())
    errorprone(libs.findLibrary("nullaway").get())

    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Every warning fails the build. "processing" is off because Error Prone sits on the
    // processor path and javac then reports each annotation no processor claims.
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing", "-Werror"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        error("NullAway")
        // Only packages annotated with @NullMarked are checked.
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:JSpecifyMode", "true")
        option("NullAway:CustomContractAnnotations", "org.springframework.lang.Contract")
    }
}

spotless {
    lineEndings = LineEnding.UNIX
    java {
        googleJavaFormat(libs.findVersion("google-java-format").get().requiredVersion).aosp()
    }
}

spotbugs {
    toolVersion = libs.findVersion("spotbugs").get().requiredVersion
    effort = Effort.MAX
}

tasks.withType<SpotBugsTask>().configureEach {
    reports.create("html") { required = true }
    reports.create("xml") { required = true }
}

// Test code is checked by the compiler and Error Prone only.
tasks.named("spotbugsTest") {
    enabled = false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
