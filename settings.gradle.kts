pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Lets Gradle download the JDK named by the toolchain when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "vitrin"

include("platform", "gateway", "core", "search")
