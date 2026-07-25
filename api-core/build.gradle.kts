plugins {
    `java-library`
}

base {
    archivesName.set("ysm-mapping-api-core")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
