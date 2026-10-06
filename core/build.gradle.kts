plugins {
    id("vitrin.spring-boot-service")
}

dependencies {
    implementation(project(":platform"))
    implementation(libs.nimbus.jose.jwt)
}
