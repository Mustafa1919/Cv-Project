plugins {
    id("vitrin.java-library")
}

dependencies {
    api(libs.jspecify)
    implementation(libs.spring.boot.autoconfigure)

    testImplementation(libs.spring.boot.starter.test)
}
