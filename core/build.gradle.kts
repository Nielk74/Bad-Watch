plugins {
    kotlin("jvm")
    `java-library`
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.dokka")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.coroutines.core)
    // `api`: the sync contract in `com.badwatch.core.sync` exposes kotlinx.serialization
    // types to both the watch app and the dashboard server, so it must leak downstream.
    api(libs.kotlin.serialization.json)
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.junit)
    testImplementation(libs.google.truth)
    testImplementation(libs.coroutines.core)
}

tasks.test {
    useJUnit()
}
