plugins {
    id("vitrin.java-library")
}

dependencies {
    api(libs.jspecify)
    implementation(libs.spring.boot.autoconfigure)
    // Token verification is shared by core and the gateway; Nimbus types stay out of the API.
    implementation(libs.nimbus.jose.jwt)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.jqwik)
}
