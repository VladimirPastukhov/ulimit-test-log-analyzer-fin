plugins {
    kotlin("jvm") version "1.9.23"
    id("com.gradleup.shadow") version "8.3.9"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.langchain4j:langchain4j-anthropic:1.22.0")
    implementation("dev.langchain4j:langchain4j-open-ai:1.22.0")
    // Pinned: newer releases depend on kotlin-reflect 2.x, whose metadata Kotlin 1.9 (IntelliJ 2023.3) cannot read.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin") { version { strictly("2.19.2") } }

    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("analyzer.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("analyzer")
    archiveClassifier.set("")
    archiveVersion.set("")
}

tasks.test {
    useJUnitPlatform()
}
