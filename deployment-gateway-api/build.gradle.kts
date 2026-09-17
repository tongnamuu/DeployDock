plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

group = rootProject.group
version = rootProject.version
repositories { mavenCentral() }
java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies { api(project(":deployment-library")) }
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "deploydock-gateway-api"
        }
    }
}
