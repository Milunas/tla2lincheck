package io.github.tla2lincheck.gradle

import io.github.tla2lincheck.generator.LincheckGenerator
import io.github.tla2lincheck.parser.TlaParser
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * GRADLE PLUGIN: tla2lincheck
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Integrates TLA+ → Lincheck test generation into the Gradle build lifecycle.
 *
 * USAGE:
 *   ```kotlin
 *   // build.gradle.kts
 *   plugins {
 *       id("io.github.tla2lincheck") version "0.1.0-SNAPSHOT"
 *   }
 *
 *   tla2lincheck {
 *       tlaSourceDir.set(file("src/main/tla"))
 *       outputDir.set(file("build/generated/tla2lincheck"))
 *       packageName.set("com.myproject.generated")
 *       threads.set(3)
 *       actorsPerThread.set(2)
 *   }
 *   ```
 *
 * The plugin:
 *   1. Registers a `generateLincheckTests` task
 *   2. Finds all `.tla` files in [tlaSourceDir]
 *   3. Parses each with [TlaParser] and generates Lincheck test with [LincheckGenerator]
 *   4. Writes generated Kotlin sources to [outputDir]
 *   5. Adds [outputDir] to the `test` source set (Kotlin or Java)
 *
 * The generated tests are compiled and executed as part of `./gradlew test`.
 */
class Tla2LincheckPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "tla2lincheck",
            Tla2LincheckExtension::class.java,
            project
        )

        project.tasks.register("generateLincheckTests", GenerateLincheckTestsTask::class.java) { task ->
            task.description = "Generates Lincheck test classes from TLA+ specifications"
            task.group = "verification"

            task.tlaSourceDir.set(extension.tlaSourceDir)
            task.outputDir.set(extension.outputDir)
            task.packageName.set(extension.packageName)
            task.threads.set(extension.threads)
            task.actorsPerThread.set(extension.actorsPerThread)
            task.iterations.set(extension.iterations)
            task.embedInvariants.set(extension.embedInvariants)
        }

        // Wire generated sources into the test source set
        project.afterEvaluate {
            val outputDir = extension.outputDir.get().asFile
            try {
                // Kotlin plugin source sets
                val kotlin = project.extensions.findByName("kotlin")
                if (kotlin != null) {
                    val sourceSets = project.extensions.getByName("sourceSets") as org.gradle.api.tasks.SourceSetContainer
                    sourceSets.getByName("test").java.srcDir(outputDir)
                }
            } catch (_: Exception) {
                // If Kotlin plugin is not applied, user must configure source sets manually
                project.logger.info("tla2lincheck: Kotlin plugin not found. Add the output directory to your test source set manually.")
            }

            // Make compileTestKotlin depend on generateLincheckTests
            project.tasks.findByName("compileTestKotlin")?.dependsOn("generateLincheckTests")
            project.tasks.findByName("compileTestJava")?.dependsOn("generateLincheckTests")
        }
    }
}

/**
 * Extension block for configuring tla2lincheck in build.gradle.kts.
 */
abstract class Tla2LincheckExtension(project: Project) {
    /** Directory containing .tla specification files */
    val tlaSourceDir: DirectoryProperty = project.objects.directoryProperty()
        .convention(project.layout.projectDirectory.dir("src/main/tla"))

    /** Output directory for generated Kotlin test sources */
    val outputDir: DirectoryProperty = project.objects.directoryProperty()
        .convention(project.layout.buildDirectory.dir("generated/tla2lincheck"))

    /** Package name for generated test classes */
    val packageName: Property<String> = project.objects.property(String::class.java)
        .convention("${project.group}.generated")

    /** Number of threads for Lincheck model checking */
    val threads: Property<Int> = project.objects.property(Int::class.java).convention(3)

    /** Actors per thread for Lincheck model checking */
    val actorsPerThread: Property<Int> = project.objects.property(Int::class.java).convention(2)

    /** Iterations for Lincheck model checking */
    val iterations: Property<Int> = project.objects.property(Int::class.java).convention(50)

    /** Whether to embed TLA+ invariant checks into generated operations */
    val embedInvariants: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)
}

/**
 * Task that generates Lincheck test classes from TLA+ specifications.
 */
abstract class GenerateLincheckTestsTask : DefaultTask() {

    @get:InputDirectory
    abstract val tlaSourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val threads: Property<Int>

    @get:Input
    abstract val actorsPerThread: Property<Int>

    @get:Input
    abstract val iterations: Property<Int>

    @get:Input
    abstract val embedInvariants: Property<Boolean>

    @TaskAction
    fun generate() {
        val sourceDir = tlaSourceDir.get().asFile
        val outDir = outputDir.get().asFile

        if (!sourceDir.exists()) {
            logger.warn("tla2lincheck: TLA+ source directory does not exist: $sourceDir")
            return
        }

        val tlaFiles = sourceDir.walkTopDown()
            .filter { it.extension == "tla" }
            .toList()

        if (tlaFiles.isEmpty()) {
            logger.warn("tla2lincheck: No .tla files found in $sourceDir")
            return
        }

        val parser = TlaParser()
        val generator = LincheckGenerator()
        val config = LincheckGenerator.Config(
            packageName = packageName.get(),
            threads = threads.get(),
            actorsPerThread = actorsPerThread.get(),
            iterations = iterations.get(),
            embedInvariants = embedInvariants.get()
        )

        // Ensure output directory exists
        val packageDir = File(outDir, packageName.get().replace('.', '/'))
        packageDir.mkdirs()

        var generated = 0
        for (tlaFile in tlaFiles) {
            logger.lifecycle("tla2lincheck: Parsing ${tlaFile.name}")

            val result = parser.parse(tlaFile.readText(), tlaFile.absolutePath)

            if (result.errors.isNotEmpty()) {
                logger.error("tla2lincheck: Errors parsing ${tlaFile.name}:")
                result.errors.forEach { logger.error("  - ${it.message}") }
                continue
            }

            result.warnings.forEach { w ->
                logger.warn("tla2lincheck: ${tlaFile.name}: ${w.message}")
            }

            val test = generator.generate(result.spec, config)
            val outputFile = File(packageDir, "${test.className}.kt")
            outputFile.writeText(test.code)

            logger.lifecycle("tla2lincheck: Generated ${test.className}.kt")
            logger.lifecycle("  Variables: ${result.spec.variables.size}, " +
                "Actions: ${result.spec.actions.size}, " +
                "Invariants: ${result.spec.invariants.size}")
            generated++
        }

        logger.lifecycle("tla2lincheck: Generated $generated test class(es) in $outDir")
    }
}
