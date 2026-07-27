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
