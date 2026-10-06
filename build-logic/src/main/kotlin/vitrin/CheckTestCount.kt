package vitrin

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a module ran a different number of tests than the repository says it should. A green
 * build proves nothing if a test was disabled, filtered out or never discovered.
 */
abstract class CheckTestCount : DefaultTask() {

    /** The expected number of executed tests per module. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val expected: RegularFileProperty

    /** Module name to the directory holding its JUnit XML reports. */
    @get:Input
    abstract val reportDirs: MapProperty<String, String>

    /** The test tasks' outputs: makes this task run after them and rerun when results change. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reports: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val wanted = Properties().apply { expected.get().asFile.inputStream().use { load(it) } }
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)

        val problems = mutableListOf<String>()
        val dirs = reportDirs.get()
        for (module in (dirs.keys + wanted.stringPropertyNames()).toSortedSet()) {
            val dir = dirs[module]
            val want = wanted.getProperty(module)?.trim()?.toIntOrNull()
            if (dir == null) {
                problems += "$module: listed in ${expected.get().asFile.name} but is not a module"
                continue
            }
            if (want == null) {
                problems += "$module: no expected count in ${expected.get().asFile.name}"
                continue
            }
            val ran =
                File(dir)
                    .listFiles { file -> file.name.endsWith(".xml") }
                    .orEmpty()
                    .sumOf { file ->
                        val suite = factory.newDocumentBuilder().parse(file).documentElement
                        suite.getAttribute("tests").toInt() - suite.getAttribute("skipped").toInt()
                    }
            if (ran != want) {
                problems += "$module: expected $want tests to run, $ran ran"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Test count check failed:\n  " + problems.joinToString("\n  "))
        }
    }
}
