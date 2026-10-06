import vitrin.CheckTestCount

plugins {
    base
    id("vitrin.java-conventions") apply false
}

val checkTestCount =
    tasks.register<CheckTestCount>("checkTestCount") {
        group = "verification"
        description = "Fails when a module ran a different number of tests than expected."
        expected = layout.projectDirectory.file("gradle/expected-tests.properties")
        subprojects.forEach { module ->
            val dir = module.layout.buildDirectory.dir("test-results/test")
            reportDirs.put(module.name, dir.map { it.asFile.path })
            reports.from(module.tasks.matching { it.name == "test" })
        }
    }

tasks.named("check") {
    dependsOn(checkTestCount)
}
