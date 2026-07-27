import java.util.zip.ZipFile
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    base
}

group = "net.okitsu.ysmmapping"
version = providers.gradleProperty("modVersion").get()

allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    pluginManager.withPlugin("java") {
        dependencies.add(
            "testRuntimeOnly",
            "org.junit.platform:junit-platform-launcher:1.11.4"
        )
    }

    tasks.withType<Jar>().configureEach {
        includeEmptyDirs = false
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

val verifyDistributions = tasks.register("verifyDistributions") {
    group = "verification"
    description = "Verifies the Fabric and NeoForge prerequisite mod distributions."
    dependsOn(":fabric:remapJar", ":neoforge:jar")
    doLast {
        val jars = listOf(
            project(":fabric").tasks.named("remapJar").get().outputs.files.singleFile,
            project(":neoforge").tasks.named("jar").get().outputs.files.singleFile
        )
        for (jar in jars) {
            ZipFile(jar).use { zip ->
                val names = zip.entries().asSequence()
                    .map { it.name }
                    .toList()
                val requiredClasses = listOf(
                    "net/okitsu/ysmmapping/api/MappingTarget.class",
                    "net/okitsu/ysmmapping/api/MappingEntry.class",
                    "net/okitsu/ysmmapping/internal/analysis/AnalysisProfile.class",
                    "net/okitsu/ysmmapping/internal/cache/MappingEngine.class"
                )

                for (required in requiredClasses) {
                    require(names.count { it == required } == 1) {
                        "$required must occur exactly once in ${jar.name}"
                    }
                }

                require(names.none { it.startsWith("com/elfmcys/yesstevemodel/") }) {
                    "Proprietary YSM classes found in ${jar.name}"
                }
                require(names.any { it.endsWith("ysm_mapping_api.mixins.json") }) {
                    "Bootstrap mixin configuration missing from ${jar.name}"
                }
                require(names.none { it.startsWith("ysm_mapping_api/reference/") }) {
                    "Version-specific YSM reference data found in ${jar.name}"
                }
            }
        }
    }
}

tasks.check {
    dependsOn(verifyDistributions)
}
