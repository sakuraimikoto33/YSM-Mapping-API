plugins {
    `java-library`
}

base {
    archivesName.set("ysm-mapping-api-core")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
