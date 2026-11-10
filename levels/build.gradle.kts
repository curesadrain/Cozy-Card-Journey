plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":engine"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    maxHeapSize = "2g"
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = false }
}

tasks.register<JavaExec>("generateLevels") {
    group = "content"
    description = "Generates, calibrates and validates all 1000 levels into app assets."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.cozycards.levels.GenerateLevelsKt")
    workingDir = rootProject.projectDir
    maxHeapSize = "3g"
    args = listOf("app/src/main/assets/levels")
}
