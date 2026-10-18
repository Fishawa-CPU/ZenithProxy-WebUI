plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.5.1"
}

group = "com.zenith.manager"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    archiveBaseName.set("BotManager")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "com.zenith.manager.Main")
    }
}

tasks.named("build") { dependsOn(tasks.named("shadowJar")) }
